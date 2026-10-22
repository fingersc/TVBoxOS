package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;

/**
 * 同步引擎 —— 单账号一次同步的编排内核。
 *
 * <p><b>v98 P1</b>：条件请求（If-None-Match / 304 早退）+ 状态统一（{@link SyncState}）
 * + 退避熔断 + 事务化应用。</p>
 *
 * <p><b>v99-r8：云端按 scope 分文件</b>（schema 3）。原来整个账号只有一个 {@code data.bin}，
 * 改一个收藏也要把全部历史 + 进度 + 收藏 + 设置整体加密上传；现在拆成
 * {@code history.bin / progress.bin / collects.bin / settings.bin}，每个文件独立 ETag，
 * <b>只上传脏的那几个</b>，单次流量约为原来的 1/3。</p>
 *
 * <p>每个 scope 独立走「读云端 → 合并 → 落库 → 需要才上传」，
 * 任一 scope 的失败都会冒泡到 {@link #runSafe} 统一计失败，不会留下半套状态
 * （{@code applyLocal} 内部是 Room 事务）。</p>
 */
public final class SyncEngine {

    /** 单次同步结果（供上层展示与统计）。 */
    public static final class Outcome {
        public boolean ok;
        public boolean notModified;      // 全部 scope 都 304
        public boolean inBackoff;        // 熔断中，未发请求
        public int requests;             // 本次实际请求数
        public long bytesDown;
        public long bytesUp;
        public String message;
        /**
         * ★ v99-r11-fix：本次同步是否因「切换账号」而作废。
         *
         * <p>{@link #runSafe} <b>不抛异常</b>（所有异常都在内部转成 Outcome），
         * 所以上层 {@code HistorySyncManager} 的 try 体里没有任何受检异常可抛 ——
         * 那些 {@code catch (IOException)} / {@code catch (SwitchAborted)} 全部不可达，
         * javac 会报 "exception X is never thrown in body of corresponding try statement"。
         * 作废这个语义必须通过返回值传递，不能靠抛异常。</p>
         */
        public boolean aborted;

        static Outcome backoff() {
            Outcome o = new Outcome();
            o.ok = true;
            o.inBackoff = true;
            o.message = "同步暂停中（稍后自动重试）";
            return o;
        }
    }

    private SyncEngine() {
    }

    /**
     * 跑一次同步。
     *
     * @param owner       账号 key
     * @param key         账号密钥
     * @param gen         切换账号代数（在途任务发现代数变化要作废）
     * @param forceUpload 强制上传（手动同步 / 切换账号后）
     */
    public static Outcome run(String owner, byte[] key, int gen, boolean forceUpload) throws Exception {
        SyncState st = SyncState.load(owner);
        // ★ P0-2：记下「本次同步开始前」的脏标记。SyncState 是整对象读改写，
        //   收尾时若直接 clearDirty()+save()，会把期间用户新标的脏一起洗掉（lost update）。
        final boolean[] dirtyBefore = snapshotDirty(st);

        // 1) 熔断中：不发任何请求
        if (st.inBackoff()) {
            // ★ P3-4：连续失败到上限是「永久熔断」，但**手动同步必须能强行恢复** ——
            //   否则用户改了网盘密码后连点「立即同步」也永远出不来，只能清数据。
            if (!forceUpload) {
                return Outcome.backoff();
            }
            LOG.i("[Sync] manual override backoff failCount=" + st.failCount);
            st.clearFailure();
            st.save();
        }

        Outcome o = new Outcome();
        long now = System.currentTimeMillis();
        String deviceId = HistorySyncManager.deviceId();
        SyncScopes enabled = SyncScopes.load();
        String accountName = AccountContext.displayName();
        if (!AccountPaths.isValidName(accountName)) {
            throw new IOException("账号名无效，请重新登录");
        }
        String dir = AccountPaths.dir(accountName);

        WebDavClient dav = new WebDavClient(
                Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""),
                Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""),
                Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));

        // 2) 目录存在性（1 个小请求）
        o.requests++;
        if (!dav.dirExists(dir)) {
            throw new IOException("账号目录不存在（可能被管理员删除）");
        }
        // 3) profile 校验（v78 起有缓存，命中后 0 请求）
        AccountService.ensureProfile(dav, accountName, key);

        // 4) 一次性迁移：旧单文件 data.bin → 4 个 scope 文件
        migrateLegacy(dav, st, key, accountName, gen, o);

        if (!enabled.anyEnabled()) {
            finish(st, now, dirtyBefore);
            Outcome r = new Outcome();
            r.ok = true;
            r.requests = o.requests;
            r.message = "同步范围已全部关闭";
            return r;
        }

        // 5) 逐 scope 同步
        boolean anyUploaded = false;
        boolean allNotModified = true;
        SyncPayload lastMerged = null;

        for (SyncScope sc : SyncScope.values()) {
            if (!enabled.isEnabled(sc)) {
                continue;
            }
            HistorySyncManager.checkGeneration(gen);
            String path = AccountPaths.scopeFile(accountName, sc);
            SyncScopes one = SyncScopes.only(sc);
            boolean dirty = dirtyBefore[sc.index];

            // 5.1 读云端：脏 ⇒ 完整 GET（合并上传都要内容）；不脏 ⇒ 条件 GET（304 省流量）
            boolean[] notModified = new boolean[1];
            String[] etagOut = new String[1];
            o.requests++;
            byte[] remoteBytes = dirty
                    ? dav.get(path, etagOut)
                    : dav.getIfNoneMatch(path, st.getEtag(sc), notModified, etagOut);

            if (notModified[0]) {
                st.setEtag(sc, etagOut[0]);
                continue;   // 该 scope 零下载 / 零解密 / 零写库
            }
            allNotModified = false;
            o.bytesDown += remoteBytes == null ? 0 : remoteBytes.length;

            SyncPayload remote = decode(key, remoteBytes, owner);

            // 5.2 epoch 变化 ⇒ 整库重置（以 history.bin 为准，清空云端时 4 个文件一起清）
            boolean reset = (sc == SyncScope.HISTORY) && remote.epoch > st.lastEpoch;
            if (reset) {
                LOG.i("[Sync] epoch reset remote=" + remote.epoch + " local=" + st.lastEpoch);
                st.lastEpoch = remote.epoch;
            }

            // 5.3 合并 + 落库
            //     ★ P0-3：重置时用「空本地视图」，不物理删库 ——
            //     否则"删了本地但上传失败"会让数据凭空消失，且下次同步还会再删一次。
            SyncPayload local = reset
                    ? HistorySyncManager.emptyLocal(owner, deviceId, now)
                    : HistorySyncManager.buildLocal(owner, deviceId, one, now);
            SyncPayload merged = SyncMerge.merge(local, remote, deviceId, now);
            HistorySyncManager.checkGeneration(gen);
            HistorySyncManager.truncateMergedHistory(merged, one);
            HistorySyncManager.truncateMergedProgress(merged, one);
            HistorySyncManager.applyLocal(merged, one);
            lastMerged = merged;

            // 5.4 与云端一致 ⇒ 跳过上传
            boolean same = remoteBytes != null && remoteBytes.length > 0
                    && HistorySyncManager.contentEquals(merged, remote);
            if (same && !forceUpload) {
                st.setEtag(sc, etagOut[0]);
                continue;
            }

            // 5.5 上传（只传这一个 scope；If-Match 防并发覆盖）
            byte[] out = Crypto.encrypt(key,
                    HistorySyncManager.GSON.toJson(merged.forScope(sc)).getBytes("UTF-8"));
            String newEtag;
            try {
                o.requests++;
                newEtag = dav.put(path, out, etagOut[0]);
            } catch (WebDavClient.ConflictException conflict) {
                // ★ P0-4：第二次 PUT 继续带 If-Match（用重拉到的 ETag），
                //   否则两台设备同时冲突重试时会静默覆盖对方。
                String[] etag2 = new String[1];
                o.requests++;
                byte[] again = dav.get(path, etag2);
                long now2 = System.currentTimeMillis();   // 用新时间戳，否则条目偏旧在 LWW 里更易输
                if (again != null && again.length > 0) {
                    SyncPayload r2 = decode(key, again, owner);
                    boolean reset2 = (sc == SyncScope.HISTORY) && r2.epoch > st.lastEpoch;
                    SyncPayload local2 = reset2
                            ? HistorySyncManager.emptyLocal(owner, deviceId, now2)
                            : HistorySyncManager.buildLocal(owner, deviceId, one, now2);
                    merged = SyncMerge.merge(local2, r2, deviceId, now2);
                    HistorySyncManager.checkGeneration(gen);
                    HistorySyncManager.truncateMergedProgress(merged, one);
                    HistorySyncManager.applyLocal(merged, one);
                    out = Crypto.encrypt(key,
                            HistorySyncManager.GSON.toJson(merged.forScope(sc)).getBytes("UTF-8"));
                }
                o.requests++;
                newEtag = dav.put(path, out, etag2[0]);
            }
            st.setEtag(sc, newEtag);
            o.bytesUp += out.length;
            anyUploaded = true;
            LOG.i("[Sync] uploaded scope=" + sc + " bytes=" + out.length);
        }

        finish(st, now, dirtyBefore);

        o.ok = true;
        o.notModified = allNotModified;
        String tail = lastMerged == null ? "" :
                "（历史 " + lastMerged.records.size() + " / 进度 " + lastMerged.progress.size()
                        + " / 收藏 " + lastMerged.collects.size() + " / 设置 " + lastMerged.settings.size() + "）";
        o.message = allNotModified ? "同步完成（无变更）" : ("同步完成" + tail);
        LOG.i("[Sync] done owner=" + owner + " req=" + o.requests
                + " down=" + o.bytesDown + " up=" + o.bytesUp + " uploaded=" + anyUploaded);
        return o;
    }

    /**
     * 把旧的单文件 {@code data.bin} 一次性迁移成 4 个 scope 文件（schema 2 → 3）。
     *
     * <p>用户已确认「一次性切换」：迁移后不再读写 data.bin，旧版本 App 同步会拿不到数据
     * （所以要求所有设备同时升级）。</p>
     *
     * <p>迁移过程幂等：中途失败下次会重来（PUT 是覆盖写），迁移成功才置 {@code splitMigrated}。
     * <b>不删除 data.bin</b>：留作人工备份，靠标记位保证不再读取它。</p>
     */
    private static void migrateLegacy(WebDavClient dav, SyncState st, byte[] key,
                                      String accountName, int gen, Outcome o) throws Exception {
        if (st.splitMigrated) {
            return;
        }
        byte[] raw;
        String[] etag = new String[1];
        o.requests++;
        try {
            raw = dav.get(AccountPaths.data(accountName), etag);
        } catch (IOException e) {
            // 读旧文件失败不阻塞同步：下次再试迁移
            LOG.i("[Sync] migrate probe failed: " + e.getMessage());
            return;
        }
        if (raw == null || raw.length == 0) {
            // 没有旧文件 = 全新账号，直接标记已迁移
            st.splitMigrated = true;
            st.save();
            return;
        }
        SyncPayload all = decode(key, raw, st.owner);
        HistorySyncManager.checkGeneration(gen);
        for (SyncScope sc : SyncScope.values()) {
            SyncPayload part = all.forScope(sc);
            byte[] out = Crypto.encrypt(key,
                    HistorySyncManager.GSON.toJson(part).getBytes("UTF-8"));
            o.requests++;
            String newEtag = dav.put(AccountPaths.scopeFile(accountName, sc), out, null);
            st.setEtag(sc, newEtag);
        }
        st.splitMigrated = true;
        if (all.epoch > st.lastEpoch) {
            st.lastEpoch = all.epoch;
        }
        st.save();
        LOG.i("[Sync] migrated data.bin -> 4 scope files owner=" + st.owner
                + " epoch=" + all.epoch);
    }

    /** 解密 + 反序列化；空数据返回空包（不是 null，后续合并要能直接用）。 */
    private static SyncPayload decode(byte[] key, byte[] raw, String owner) throws Exception {
        SyncPayload p;
        if (raw == null || raw.length == 0) {
            p = new SyncPayload();
            p.owner = owner;
        } else {
            String json = new String(Crypto.decrypt(key, raw), "UTF-8");
            p = HistorySyncManager.GSON.fromJson(json, SyncPayload.class);
            if (p == null) {
                p = new SyncPayload();
                p.owner = owner;
            }
            if (p.deleted == null) {
                p.deleted = new SyncPayload.Deleted();
            }
        }
        return p;
    }

    private static boolean[] snapshotDirty(SyncState st) {
        if (st.dirty == null || st.dirty.length == 0) {
            return new boolean[SyncScope.COUNT];
        }
        return st.dirty.clone();
    }

    /**
     * 清空云端（整库重置）。**走与同步同一条路径**，不再自己拼一套 WebDAV 流程。
     *
     * <p>语义：云端 4 个文件都写成「空 + epoch+1」，同时<b>清空本机</b>并更新本地 epoch。</p>
     *
     * <p><b>为什么必须清本机</b>：旧实现只清云端、并把 {@code lastEpoch} 更新成新值 ——
     * 结果是本机之后再也不会触发 reset（{@code remote.epoch > lastEpoch} 不成立），
     * 而下一次同步会把本机数据原样合并上传，<b>刚清空的云端立刻被恢复</b>。
     * 所以这里清完云端就顺手清本机，语义才自洽。</p>
     */
    public static Outcome clearCloud(String owner, byte[] key, int gen) {
        try {
            SyncState st = SyncState.load(owner);
            SyncScopes scopes = SyncScopes.load();
            String deviceId = HistorySyncManager.deviceId();
            String accountName = AccountContext.displayName();
            if (!AccountPaths.isValidName(accountName)) {
                return fail("账号名无效，请重新登录");
            }
            WebDavClient dav = new WebDavClient(
                    Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""),
                    Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""),
                    Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));
            if (!dav.dirExists(AccountPaths.dir(accountName))) {
                return fail("账号目录不存在（可能被管理员删除）");
            }
            AccountService.ensureProfile(dav, accountName, key);

            Outcome o = new Outcome();
            // 先把旧单文件迁移掉，否则下次同步会把 data.bin 当数据源又搬回来
            migrateLegacy(dav, st, key, accountName, gen, o);

            // 新 epoch 取「本地记录」与「云端 history.bin」两者的 max + 1，避免本机状态落后时倒退
            long base = st.lastEpoch;
            try {
                byte[] hb = dav.get(AccountPaths.scopeFile(accountName, SyncScope.HISTORY), null);
                if (hb != null && hb.length > 0) {
                    base = Math.max(base, decode(key, hb, owner).epoch);
                }
            } catch (IOException ignored) {
                // 读不到就用本机记录，够用
            }
            final long epoch = base + 1;
            final long now = System.currentTimeMillis();

            for (SyncScope sc : SyncScope.values()) {
                SyncPayload empty = new SyncPayload();
                empty.owner = owner;
                empty.epoch = epoch;
                empty.updatedAt = now;
                empty.dev = deviceId;
                byte[] out = Crypto.encrypt(key,
                        HistorySyncManager.GSON.toJson(empty.forScope(sc)).getBytes("UTF-8"));
                String newEtag = dav.put(AccountPaths.scopeFile(accountName, sc), out, null);
                st.setEtag(sc, newEtag);
            }

            // 清本机：否则下次同步会把本机数据又合并上传，等于没清
            HistorySyncManager.wipeLocal(scopes);

            st.lastEpoch = epoch;
            st.lastAt = now;
            st.clearDirty();
            st.clearFailure();
            st.save();

            Outcome r = new Outcome();
            r.ok = true;
            r.requests = o.requests + SyncScope.COUNT;
            r.message = "已清空云端（其它设备下次同步会跟随清空）";
            LOG.i("[Sync] clearCloud done owner=" + owner + " epoch=" + epoch);
            return r;
        } catch (WebDavClient.AuthException e) {
            return fail("认证失败：请检查网盘账号与应用密码");
        } catch (Throwable t) {
            LOG.e("[Sync] clearCloud failed", t);
            return fail("清空失败：" + t.getMessage());
        }
    }

    /**
     * 带异常收尾的入口：内部把失败/熔断写进 {@link SyncState}，<b>绝不抛异常</b>。
     *
     * <p>上层（HSM）因此不需要再自己 {@code noteFailure()}，避免双重计数。</p>
     */
    public static Outcome runSafe(String owner, byte[] key, int gen, boolean forceUpload) {
        try {
            return run(owner, key, gen, forceUpload);
        } catch (HistorySyncManager.SwitchAborted e) {
            // 切换账号作废：不计失败
            // ★ v99-r11-fix：通过返回值告知上层"这是作废、不是失败"（runSafe 不抛异常）
            Outcome o = fail(e.getMessage() == null ? "已取消" : e.getMessage());
            o.aborted = true;
            return o;
        } catch (WebDavClient.AuthException e) {
            recordFailure(owner, true);
            return fail("认证失败：请检查网盘账号与应用密码");
        } catch (SecurityException e) {
            recordFailure(owner, false);
            return fail("账号或密码不正确（解密失败）");
        } catch (WebDavClient.ConflictException e) {
            recordFailure(owner, false);
            return fail("云端被其它设备修改，重试后仍冲突");
        } catch (IOException e) {
            recordFailure(owner, false);
            return fail("网络或服务器错误：" + e.getMessage());
        } catch (Throwable t) {
            recordFailure(owner, false);
            LOG.e("[Sync] engine failed", t);
            return fail("同步失败：" + t.getClass().getSimpleName());
        }
    }

    private static void recordFailure(String owner, boolean auth) {
        try {
            SyncState st = SyncState.load(owner);
            if (auth) {
                st.noteAuthFailure();
            } else {
                st.noteFailure();
            }
            st.save();
        } catch (Throwable ignored) {
        }
    }

    private static Outcome fail(String msg) {
        Outcome o = new Outcome();
        o.ok = false;
        o.message = msg;
        return o;
    }

    /**
     * 成功后的统一收尾。
     *
     * <p>★ P0-2：<b>不能</b> {@code st.clearDirty() + st.save()}。
     * {@code st} 是同步开始时的快照，而 {@code save()} 会序列化整个实例 ——
     * 同步期间用户新 markDirty 的标记会被这份旧快照整体覆盖（lost update）。
     * 这里改为：重新读最新状态 → 只清「本次同步开始前就已存在」的 scope →
     * 再把本次读到/写入的各 scope ETag 合进去 → 落盘。</p>
     */
    private static void finish(SyncState st, long now, boolean[] dirtyBefore) {
        synchronized (SyncState.class) {
            SyncState cur = SyncState.load(st.owner);
            cur.lastAt = now;
            if (st.lastEpoch > cur.lastEpoch) {
                cur.lastEpoch = st.lastEpoch;
            }
            if (st.etags != null) {
                for (int i = 0; i < st.etags.length && i < SyncScope.COUNT; i++) {
                    String e = st.etags[i];
                    if (e != null && !e.isEmpty()) {
                        cur.setEtag(SyncScope.values()[i], e);
                    }
                }
            }
            if (dirtyBefore != null) {
                for (int i = 0; i < cur.dirty.length && i < dirtyBefore.length; i++) {
                    if (dirtyBefore[i]) {
                        cur.dirty[i] = false;   // 只清"本次同步确实覆盖到"的那些
                    }
                }
            }
            cur.clearFailure();
            cur.save();
        }
        SettingsProfile.setPendingPull(st.owner, false);
        SyncTombstones.clear(now);
    }
}
