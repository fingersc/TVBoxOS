package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;

/**
 * 同步引擎（v98 P1）—— 单账号一次同步的编排内核。
 *
 * <p>与旧 {@code HistorySyncManager#doSync} 的差别：</p>
 * <ol>
 *   <li><b>条件请求</b>：带 {@code If-None-Match}，服务端 304 ⇒ 零下载/零解密/零解析/零写库早退</li>
 *   <li><b>状态统一</b>：读写 {@link SyncState}（ETag / 按 scope 脏 / 退避熔断）</li>
 *   <li><b>退避熔断</b>：失败阶梯 + 401/403 立即停，期间不发任何请求</li>
 *   <li><b>事务化应用</b>：复用 {@link HistorySyncManager#applyLocal}（三张表同事务）</li>
 * </ol>
 *
 * <p>合并 / 裁剪 / 冲突重试等逻辑仍复用 HSM 既有实现，保证与旧路径行为等价。</p>
 */
public final class SyncEngine {

    /** 单次同步结果（供上层展示与统计）。 */
    public static final class Outcome {
        public boolean ok;
        public boolean notModified;      // 304 早退
        public boolean inBackoff;        // 熔断中，未发请求
        public int requests;             // 本次实际请求数
        public long bytesDown;
        public long bytesUp;
        public String message;

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
        // ★ v99-r7 P0-2：记下「本次同步开始前」的脏标记。
        //   SyncState 是整对象读改写（save() 会序列化整个实例），若收尾时直接
        //   st.clearDirty() + st.save()，会把「同步期间用户新 markDirty」的标记一起洗掉
        //   （lost update）⇒ 本地已有改动但 dirty=false ⇒ 不再自动同步。
        //   所以收尾时只清这份快照里为 true 的 scope。
        final boolean[] dirtyBefore = st.dirty == null ? new boolean[4] : st.dirty.clone();

        // 1) 熔断中：不发任何请求
        if (st.inBackoff()) {
            return Outcome.backoff();
        }

        Outcome o = new Outcome();
        long now = System.currentTimeMillis();
        String deviceId = HistorySyncManager.deviceId();
        SyncScopes scopes = SyncScopes.load();
        String accountName = AccountContext.displayName();
        if (!AccountPaths.isValidName(accountName)) {
            throw new IOException("账号名无效，请重新登录");
        }
        String path = AccountPaths.data(accountName);

        WebDavClient dav = new WebDavClient(
                Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""),
                Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""),
                Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));

        // 2) 目录存在性（1 个小请求）
        o.requests++;
        if (!dav.dirExists(AccountPaths.dir(accountName))) {
            throw new IOException("账号目录不存在（可能被管理员删除）");
        }
        // 3) profile 校验（v78 起有缓存，命中后 0 请求）
        AccountService.ensureProfile(dav, accountName, key);

        // 4) 读取云端
        //    v99-r5 ★：本地有未上传改动时**不能**走条件请求（If-None-Match）——
        //    304 只说明"云端没变"，却拿不到云端内容，而合并+上传必须有云端内容。
        //    旧实现在这里 304 早退后顺手 clearDirty()，把本地刚标的脏洗掉了：
        //    表现为云端 data.bin 一直不更新，App 却显示「上次同步：xx（正常）」。
        //    所以：脏 ⇒ 完整 GET（合并后上传）；不脏 ⇒ 条件 GET（304 早退省流量）。
        HistorySyncManager.checkGeneration(gen);
        boolean localDirty = st.anyDirty();
        boolean[] notModified = new boolean[1];
        String[] etagOut = new String[1];
        o.requests++;
        byte[] remoteBytes = localDirty
                ? dav.get(path, etagOut)
                : dav.getIfNoneMatch(path, st.etag, notModified, etagOut);
        o.bytesDown = remoteBytes == null ? 0 : remoteBytes.length;

        // 5) 304 早退：零下载 / 零解密 / 零解析 / 零写库（只有"本地不脏"才可能走到这）
        if (notModified[0]) {
            // ★ v99-r7 P0-2：改为「重新读最新状态再写」，且完全不碰 dirty。
            //   原来直接用开头那份 st 快照 save()，会把同步期间新标的脏标记整体覆盖掉。
            synchronized (SyncState.class) {
                SyncState cur = SyncState.load(owner);
                cur.lastAt = now;
                if (etagOut[0] != null && !etagOut[0].isEmpty()) {
                    cur.etag = etagOut[0];
                }
                // v99-r5 ★：304 只证明"云端没变"，不能顺手 clearDirty()
                cur.clearFailure();
                cur.save();
            }
            LOG.i("[Sync] 304 not modified owner=" + owner + " req=" + o.requests);
            o.ok = true;
            o.notModified = true;
            o.message = "同步完成（无变更）";
            return o;
        }

        // 6) 解密
        SyncPayload remote;
        if (remoteBytes == null || remoteBytes.length == 0) {
            remote = new SyncPayload();
            remote.owner = owner;
        } else {
            String json = new String(Crypto.decrypt(key, remoteBytes), "UTF-8");
            remote = HistorySyncManager.GSON.fromJson(json, SyncPayload.class);
            if (remote == null) {
                remote = new SyncPayload();
                remote.owner = owner;
            }
            if (remote.deleted == null) {
                remote.deleted = new SyncPayload.Deleted();
            }
        }

        // 7) epoch 变化 ⇒ 整库重置
        //   ★ v99-r7 P0-3：**不再** wipeLocal() 物理删库。
        //   旧实现在这里就把本地三张表删掉，而此刻整个流程还没有任何成功保证：
        //   "epoch 变更 + 上传失败"时 st.lastEpoch 不会更新 ⇒ 下次同步又删一次，
        //   把期间用户新产生的数据也清掉 —— 只要网络一直不好，数据会被反复清空。
        //   改为「本地位视图用空包参与合并」：上传失败则本地分毫未动；
        //   上传成功后 applyLocal(merged) 本来就会按合并结果重写三张表，效果等同于重置。
        //   注意：必须用空包代替本地数据，否则 merge 时 remote 是空包、
        //   local 的旧条目会全部胜出（pickRecord(a,null)=a），等于抵消掉整库重置。
        boolean resetLocal = remote.epoch > st.lastEpoch;
        HistorySyncManager.checkGeneration(gen);
        if (resetLocal) {
            LOG.i("[Sync] epoch reset remote=" + remote.epoch + " local=" + st.lastEpoch);
        }

        // 8) 合并 + 裁剪 + 事务化应用
        SyncPayload local = resetLocal
                ? HistorySyncManager.emptyLocal(owner, deviceId, now)
                : HistorySyncManager.buildLocal(owner, deviceId, scopes, now);
        SyncPayload merged = SyncMerge.merge(local, remote, deviceId, now);
        HistorySyncManager.checkGeneration(gen);
        HistorySyncManager.truncateMergedHistory(merged, scopes);
        HistorySyncManager.applyLocal(merged, scopes);

        // 9) 与云端一致 ⇒ 跳过上传
        boolean same = remoteBytes != null && remoteBytes.length > 0
                && HistorySyncManager.contentEquals(merged, remote);
        if (same && !forceUpload) {
            finish(st, now, etagOut[0], merged, dirtyBefore);
            LOG.i("[Sync] no change, skip upload owner=" + owner
                    + " rec=" + merged.records.size() + " prog=" + merged.progress.size());
            o.ok = true;
            o.message = "同步完成（无变更）：历史 " + merged.records.size()
                    + " / 进度 " + merged.progress.size()
                    + " / 收藏 " + merged.collects.size()
                    + " / 设置 " + merged.settings.size();
            return o;
        }

        // 10) 上传（If-Match；冲突则重拉、重合并、再写一次）
        byte[] out = Crypto.encrypt(key, HistorySyncManager.GSON.toJson(merged).getBytes("UTF-8"));
        try {
            o.requests++;
            dav.put(path, out, etagOut[0]);
        } catch (WebDavClient.ConflictException conflict) {
            // ★ v99-r7 P0-4：第二次 PUT **必须继续带 If-Match**（用重拉拿到的 ETag）。
            //   原来传 null 等于放弃并发保护：两台设备同时进入冲突重试时，
            //   后到达的那次会静默覆盖前者刚写入的数据（前者却以为成功了）。
            //   只重试一次，再冲突就抛给上层退避，避免无界循环。
            String[] etag2 = new String[1];
            o.requests++;
            byte[] again = dav.get(path, etag2);
            // 用新时间戳重算：复用最初的 now 会让条目时间戳偏旧，在 LWW 里更容易输给对方
            long now2 = System.currentTimeMillis();
            if (again != null && again.length > 0) {
                SyncPayload r2 = HistorySyncManager.GSON.fromJson(
                        new String(Crypto.decrypt(key, again), "UTF-8"), SyncPayload.class);
                if (r2 != null) {
                    if (r2.deleted == null) {
                        r2.deleted = new SyncPayload.Deleted();
                    }
                    SyncPayload local2 = resetLocal
                            ? HistorySyncManager.emptyLocal(owner, deviceId, now2)
                            : HistorySyncManager.buildLocal(owner, deviceId, scopes, now2);
                    merged = SyncMerge.merge(local2, r2, deviceId, now2);
                    HistorySyncManager.checkGeneration(gen);
                    HistorySyncManager.applyLocal(merged, scopes);
                    out = Crypto.encrypt(key, HistorySyncManager.GSON.toJson(merged).getBytes("UTF-8"));
                }
            }
            o.requests++;
            dav.put(path, out, etag2[0]);
        }

        HistorySyncManager.checkGeneration(gen);
        o.bytesUp = out.length;
        finish(st, now, etagOut[0], merged, dirtyBefore);
        LOG.i("[Sync] done owner=" + owner + " rec=" + merged.records.size()
                + " prog=" + merged.progress.size() + " req=" + o.requests);
        o.ok = true;
        o.message = "同步完成：历史 " + merged.records.size()
                + " / 进度 " + merged.progress.size()
                + " / 收藏 " + merged.collects.size()
                + " / 设置 " + merged.settings.size();
        return o;
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
            return fail(e.getMessage() == null ? "已取消" : e.getMessage());
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
     * <p>★ v99-r7 P0-2：<b>不能</b> {@code st.clearDirty() + st.save()}。
     * {@code st} 是同步开始时加载的快照，而 {@code save()} 会序列化整个实例 ——
     * 同步期间用户新 markDirty 的标记会被这份旧快照整体覆盖（lost update）。
     * 这里改为：重新读最新状态 → 只清「本次同步开始前就已存在」的 scope → 再落盘。
     * </p>
     */
    private static void finish(SyncState st, long now, String etag, SyncPayload merged, boolean[] dirtyBefore) {
        synchronized (SyncState.class) {
            SyncState cur = SyncState.load(st.owner);
            cur.lastAt = now;
            cur.lastEpoch = merged.epoch;
            if (etag != null && !etag.isEmpty()) {
                cur.etag = etag;
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
