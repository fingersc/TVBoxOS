package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.security.GeneralSecurityException;

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
        return run(owner, key, gen, forceUpload, false);
    }

    /**
     * ★ v4.2.2（B）：冷启动首轮「轻量同步」。
     *
     * <p>首页「最近观看」只需要 HISTORY（+ 账号档位所在的 SETTINGS），而 PROGRESS/COLLECT
     * 每个 scope 都要一次条件 GET、脏时还要 PUT，把首屏等待从 ~2s 拉长到 2.5s+。
     * {@code lightFirstRound=true} 时本轮只处理 SETTINGS + HISTORY，其余 scope 保持脏标记，
     * 由调用方紧接着排一轮补跑。</p>
     *
     * <p>注意：<b>不会</b>因此少传数据 —— 被跳过的 scope 的 {@code processedOk} 为 false，
     * {@link #finish} 不会清它们的 dirty，补跑那轮照常上传。</p>
     */
    public static Outcome run(String owner, byte[] key, int gen, boolean forceUpload,
                              boolean lightFirstRound) throws Exception {
        SyncState st = SyncState.load(owner);
        // ★ P0-2：记下「本次同步开始前」的脏标记。SyncState 是整对象读改写，
        //   收尾时若直接 clearDirty()+save()，会把期间用户新标的脏一起洗掉（lost update）。
        final boolean[] dirtyBefore = snapshotDirty(st);
        // ★ 清空云端（clearCloud）会把 3 个数据文件的 epoch 都 +1；若逐个 scope 用
        //   “remote.epoch > st.lastEpoch”判断，SETTINGS/HISTORY 先跑时先把 lastEpoch 抬到新值，
        //   后面的 PROGRESS/COLLECT 就判不出 reset，云端已清空、本机却把旧进度又合并上传回来。
        //   因此用“本轮开始前”的 epoch 作为统一水位，所有 scope 口径一致。
        final long epochBefore = st.lastEpoch;

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
        // v4：使用可信时间（本机时钟 + 云端 Date 偏移）作为本轮基准；
        // 同时作为 finish 里墓碑清理的水位（墓碑 at 也是 HLC physical，两者口径一致）。
        long now = TrustedClock.nowPhysical();
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

        // 2) 目录存在性 + 一次性批量 ETag（v4.1：Depth:1 取代 dirExists，省后续 recoverEtag）
        o.requests++;
        java.util.Map<String, String> dirEntries = dav.propfindDepth1(dir);
        if (dirEntries == null) {
            throw new IOException("账号目录不存在（可能被管理员删除）");
        }
        // 2.1) 读取 sync.meta（设备水位 / 30 天回归判定）
        SyncMeta meta = loadMeta(dav, key, accountName, owner, o, st, dirEntries);
        boolean staleDevice = meta.isStale(deviceId, now);
        boolean downloadOnly = staleDevice && !forceUpload;
        if (downloadOnly) {
            LOG.i("[Sync] stale device " + deviceId + " (" + ((now - metaLastSeen(meta, deviceId)) / 86400000L)
                    + "d) -> download only (no upload)");
        }
        // 3) profile 校验（v78 起有缓存，命中后 0 请求）
        AccountService.ensureProfile(dav, accountName, key);

        // 4) 一次性迁移：旧单文件 data.bin → 4 个 scope 文件
        migrateLegacy(dav, st, key, accountName, gen, o);

        if (!enabled.anyEnabled()) {
            // 全部关闭时不要清任何 dirty（否则重新启用后删除/改动不会上传）。
            finish(st, now, new boolean[SyncScope.COUNT],
                    false, false, false, Hlc.ZERO, false);
            Outcome r = new Outcome();
            r.ok = true;
            r.requests = o.requests;
            r.message = "同步范围已全部关闭";
            return r;
        }

        // ★ 协议 v4：同步「之前」先按本机策略收敛一次（清坏记录 / 同名去重 / 按 HLC 档位裁剪）。
        //   必须放在 history scope 构建与上传之前：dedupeSameName 产生的墓碑要能随本轮 payload 上传，
        //   否则同步结束后才删，会被 finish 的墓碑清理（clearTombstones）直接抹掉删除意图，
        //   造成「本机删了、云端还在，下轮又回来」。
        boolean pruned = false;
        if (enabled.history) {
            try {
                pruned = com.github.tvbox.osc.cache.RoomDataManger.pruneHistory();
            } catch (Throwable t) {
                LOG.e("[Sync] prune before sync failed", t);
            }
            if (pruned) {
                // 本次收敛改动了本机历史：让本轮 history scope 走「完整 GET + 合并 + 上传」，
                // 而不是被 304 提前返回（dirtyBefore 是同步开始时的快照，不含收敛产生的脏）。
                dirtyBefore[SyncScope.HISTORY.index] = true;
            }
        }

        // 5) 逐 scope 同步
        // 墓碑清理水位：只清「同步开始前已存在、且本轮确实上传成功」的那几类墓碑。
        //   HlcClock.peek() 不分配新值；prune 产生的墓碑在其之前已计入。
        Hlc tombWatermark = HlcClock.peek();
        boolean anyUploaded = false;
        boolean allNotModified = true;
        boolean metaDirty = false;
        SyncPayload lastMerged = null;
        // ★ v99-r14：本次是否修复过损坏的云端文件（用于最终提示，见下方 message）
        boolean repaired = false;
        // ★ r169：本次同步是否真的改动了本机数据（用于落库后通知界面刷新历史/收藏）
        boolean localChanged = false;

        java.util.Set<SyncScope> failedScopes = new java.util.HashSet<>();
        boolean[] processedOk = new boolean[SyncScope.COUNT];
        for (SyncScope sc : orderedScopes()) {
            if (!enabled.isEnabled(sc)) {
                continue;
            }
            // ★ v4.2.2（B）：首轮轻量同步只跑 SETTINGS + HISTORY，其余 scope 留到补跑那轮。
            //   processedOk 保持 false ⇒ finish 不清它的 dirty ⇒ 补跑照常上传，不会丢改动。
            if (lightFirstRound && sc != SyncScope.SETTINGS && sc != SyncScope.HISTORY) {
                LOG.i("[Sync] light first round: defer scope=" + sc);
                continue;
            }
            try {
            HistorySyncManager.checkGeneration(gen);
            String path = AccountPaths.scopeFile(accountName, sc);
            SyncScopes one = SyncScopes.only(sc);
            boolean dirty = dirtyBefore[sc.index];

            // v4.1.1: directory PROPFIND already gave every scope ETag.  If this
            // scope is not dirty and its ETag is unchanged, skip the conditional
            // GET entirely (one less round-trip per unchanged scope).
            String knownEtag = dirEntries == null ? null : dirEntries.get(sc.fileName);
            if (!dirty && knownEtag != null && knownEtag.length() > 0
                    && knownEtag.equals(st.getEtag(sc))) {
                processedOk[sc.index] = true;
                continue;
            }

            // 5.1 读云端：脏 ⇒ 完整 GET（合并上传都要内容）；不脏 ⇒ 条件 GET（304 省流量）
            boolean[] notModified = new boolean[1];
            String[] etagOut = new String[1];
            o.requests++;
            byte[] remoteBytes = dirty
                    ? dav.get(path, etagOut)
                    : dav.getIfNoneMatch(path, st.getEtag(sc), notModified, etagOut);

            if (notModified[0]) {
                if (etagOut[0] != null && etagOut[0].length() > 0) {
                    st.setEtag(sc, etagOut[0]);
                }
                processedOk[sc.index] = true;
                continue;   // 该 scope 零下载 / 零解密 / 零写库
            }
            allNotModified = false;
            o.bytesDown += remoteBytes == null ? 0 : remoteBytes.length;

            // ★ v99-r14：解密失败先定性，再决定怎么办。
            //
            //   decrypt() 有三种失败形态：
            //     · SecurityException("bad mac")            —— HMAC 不匹配
            //     · IllegalArgumentException("bad magic/length") —— 文件结构坏（被截断的典型症状）
            //     · GeneralSecurityException(BadPadding)    —— 密文被改
            //
            //   旧代码把它们一律算作失败，前一种还被 runSafe 归成「账号或密码不正确」——
            //   这是误判：密码校验本该走 profile.json（明文 + 派生 verify，独立通道），
            //   数据文件解密失败推不出密码错误。
            //
            //   现在先用 profile.json 复核：
            //     · 复核通过 ⇒ 密钥是对的 ⇒ 只能是云端文件损坏 ⇒ 用本机数据重传覆盖（自愈）
            //     · 复核失败 ⇒ 才是真的密钥不匹配（账号被重置 / 用别的密码登同名目录）
            //                 ⇒ 抛错，且【不重传】，避免用错密钥的数据覆盖云端
            SyncPayload remote;
            try {
                remote = decode(key, remoteBytes, owner);
            } catch (SecurityException | GeneralSecurityException
                    | IllegalArgumentException corrupt) {
                if (AccountService.verifyPassword(dav, accountName, key)) {
                    LOG.i("[Sync] scope=" + sc + " cloud file corrupt ("
                            + corrupt.getClass().getSimpleName() + "), repair by re-upload");
                    // 以「空远端」继续：5.3 合并后等于本机数据，5.5 的 same 判定必然为 false
                    //   ⇒ 走上传分支，用本机完整数据覆盖掉损坏的文件。
                    remote = HistorySyncManager.emptyLocal(owner, deviceId, now);
                    repaired = true;
                } else {
                    throw new IOException("账号密码与云端不匹配（账号可能被管理员重置，"
                            + "或用了不同密码登录同名目录）");
                }
            }

            // ★ 协议 v4 接收规则：读到云端包后先推进本地 HLC，保证本机后续写入的
            //   HLC 一定大于云端已有条目（否则慢钟设备的新数据永远输给云端旧记录）。
            HlcClock.observe(remote.hlcMaxPhysical, remote.hlcMaxCounter);

            // 5.2 epoch 变化 ⇒ 整库重置（以 history.bin 为准；清空云端时 3 个数据文件一起清，
            //     settings 不在清空范围内，其 epoch 不参与此判断）
            boolean reset = remote.epoch > epochBefore;
            if (reset) {
                LOG.i("[Sync] epoch reset remote=" + remote.epoch + " local=" + st.lastEpoch);
                st.lastEpoch = remote.epoch;
            }

            // 5.3 合并 + 落库
            //     ★ P0-3：重置时用「空本地视图」，不物理删库 ——
            //     否则"删了本地但上传失败"会让数据凭空消失，且下次同步还会再删一次。
            SyncPayload local = (reset || (downloadOnly && remoteBytes != null && remoteBytes.length > 0))
                    ? HistorySyncManager.emptyLocal(owner, deviceId, now)
                    : HistorySyncManager.buildLocal(owner, deviceId, one, now);
            // downloadOnly 也是“以云端覆盖本机”，必须让 applyLocal 用空本地视图重建，
            // 否则事务内 fresh=buildLocal 会把本机旧行重新并回去，等下次不再 stale 时又上传污染云端。
            boolean replaceLocal = reset || (downloadOnly && remoteBytes != null && remoteBytes.length > 0);
            SyncPayload merged = SyncMerge.merge(local, remote, deviceId, now);
            HlcClock.observe(merged.hlcMaxPhysical, merged.hlcMaxCounter);
            HistorySyncManager.checkGeneration(gen);
            HistorySyncManager.truncateMergedHistory(merged, one);
            HistorySyncManager.truncateMergedProgress(merged, one);
            if (HistorySyncManager.applyLocal(merged, one, replaceLocal)) localChanged = true;
            lastMerged = merged;

            // ★ v4.1：记录本设备已看到的水位，并在「所有活跃设备都已看到」时回收墓碑。
            //   保守条件：至少 2 台活跃设备 + 墓碑存在超过 24 小时（见 SyncMeta.dropSeenTombs）。
            // ★ v4.2.3：只有「水位真的推进」或「墓碑真的被回收」才算 meta 脏。
            //   原来无条件置脏，使下方 saveMeta 的「6 小时一次」门槛完全失效 ——
            //   实测 100 条上传里 42 条是 sync.meta。注意 meta.touch(deviceId, now)
            //   仍然每轮执行（在 saveMeta 段、且不在 if 内），只是「落盘」受门槛控制：
            //   否则一台活着、常在同步但数据长期不变的设备，云端 lastSeenAt 会一直不动，
            //   攒到 30 天被 SyncMeta.isStale 判成 stale → downloadOnly + replaceLocal，
            //   用云端覆盖本机，把本机独有数据清掉。
            if (meta != null && meta.reliable) {
                boolean seenChanged;
                if (sc == SyncScope.HISTORY) {
                    seenChanged = meta.markSeenRecords(deviceId, merged.hlcMaxPhysical, merged.hlcMaxCounter);
                } else if (sc == SyncScope.COLLECT) {
                    seenChanged = meta.markSeenCollects(deviceId, merged.hlcMaxPhysical, merged.hlcMaxCounter);
                } else {
                    seenChanged = false;
                }
                int tombsDropped = meta.dropSeenTombs(merged, now, 24L * 3600L * 1000L);
                if (seenChanged || tombsDropped > 0) {
                    metaDirty = true;
                }
            }

            // ★ v4.1：30 天未上线的设备「只下载、不上传」——把云端数据落到本机后直接结束该 scope，
            //   避免长期离线设备的旧数据（尤其旧墓碑/旧删除）反过来污染云端。
            if (downloadOnly && remoteBytes != null && remoteBytes.length > 0) {
                if (etagOut[0] != null && etagOut[0].length() > 0) {
                    st.setEtag(sc, etagOut[0]);
                }
                // 本机已与云端对齐到该水位（只是不上传），记下来避免下次启动被误判为「有未推送写入」。
                st.bumpPushedHlc(sc, merged.hlcMaxPhysical, merged.hlcMaxCounter);
                processedOk[sc.index] = true;
                LOG.i("[Sync] download-only scope=" + sc + " applied " + merged.forScope(sc).records.size()
                        + " records");
                continue;
            }

            // 5.4 与云端一致 ⇒ 跳过上传
            //   ★ v4.2.3 两道补充：
            //   (1) 水位守卫：same 分支下面同样会 bumpPushedHlc（把 merged 的 hlcMax 记成「已推送」）。
            //       若 merged 的 HLC 实际高于云端却跳过上传，这条 HLC 就永远上不了云 ——
            //       下次启动 reconcilePushedHlc 见 db.maxHlc == pushedHlc 不会补标脏，
            //       其它设备的「最近观看」顺序拿不到这次更新，还可能被更低的 HLC 反超。
            //       所以只有「本机水位不高于云端」才允许跳过。
            //   (2) 补日志：这个分支以前一行日志都没有，出问题时无法判断「没上传」
            //       是因为内容一致，还是异常静默跳过。
            boolean contentSame = remoteBytes != null && remoteBytes.length > 0
                    && HistorySyncManager.contentEquals(merged, remote);
            boolean waterOk = contentSame
                    && Hlc.of(merged.hlcMaxPhysical, merged.hlcMaxCounter, "", 0L)
                    .compareIgnoringDev(Hlc.of(remote.hlcMaxPhysical, remote.hlcMaxCounter, "", 0L)) <= 0;
            boolean same = contentSame && waterOk;
            if (same && !forceUpload) {
                if (etagOut[0] != null && etagOut[0].length() > 0) {
                    st.setEtag(sc, etagOut[0]);
                }
                // 云端已包含合并结果（内容一致），记录已推送水位，避免启动对账误判为未推送。
                st.bumpPushedHlc(sc, merged.hlcMaxPhysical, merged.hlcMaxCounter);
                processedOk[sc.index] = true;
                LOG.i("[Sync] skip same scope=" + sc + " bytes=" + remoteBytes.length);
                continue;
            }
            // ★ v4.2.3-diag：没跳过就说明「内容不同」或「水位更高」。分别标记原因并给出差异摘要，
            //   一次真机即可定位是 contentEquals 判不等（含异常被吞）还是水位守卫拦下。
            if (remoteBytes != null && remoteBytes.length > 0) {
                LOG.i("[Sync] same-miss scope=" + sc
                        + " content=" + contentSame
                        + " water=" + (contentSame ? String.valueOf(waterOk) : "-")
                        + " force=" + forceUpload
                        + " " + HistorySyncManager.diffSummary(merged, remote));
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
                long now2 = TrustedClock.nowPhysical();   // 用新时间戳，否则条目偏旧在 LWW 里更易输
                if (again != null && again.length > 0) {
                    SyncPayload r2 = decode(key, again, owner);
                    HlcClock.observe(r2.hlcMaxPhysical, r2.hlcMaxCounter);
                    boolean reset2 = r2.epoch > epochBefore;
                    SyncPayload local2 = reset2
                            ? HistorySyncManager.emptyLocal(owner, deviceId, now2)
                            : HistorySyncManager.buildLocal(owner, deviceId, one, now2);
                    merged = SyncMerge.merge(local2, r2, deviceId, now2);
                    HlcClock.observe(merged.hlcMaxPhysical, merged.hlcMaxCounter);
                    HistorySyncManager.checkGeneration(gen);
                    HistorySyncManager.truncateMergedHistory(merged, one);
                    HistorySyncManager.truncateMergedProgress(merged, one);
                    if (HistorySyncManager.applyLocal(merged, one, reset2)) localChanged = true;
                    out = Crypto.encrypt(key,
                            HistorySyncManager.GSON.toJson(merged.forScope(sc)).getBytes("UTF-8"));
                }
                o.requests++;
                newEtag = dav.put(path, out, etag2[0]);
            }
            // ★ v4.1：不再每 scope 单独 PROPFIND 补 ETag（省请求）；
            //   循环结束后若发生过上传，用一次 Depth:1 批量刷新所有 scope 的 ETag。
            if (newEtag != null && newEtag.length() > 0) {
                st.setEtag(sc, newEtag);
            }
            o.bytesUp += out.length;
            anyUploaded = true;
            processedOk[sc.index] = true;
            // ★ v4.1：记录本 scope 已成功推送的 HLC 水位，用于启动时判断「本机是否还有未推送的写入」。
            st.bumpPushedHlc(sc, merged.hlcMaxPhysical, merged.hlcMaxCounter);
            LOG.i("[Sync] uploaded scope=" + sc + " bytes=" + out.length);
            } catch (WebDavClient.AuthException ae) {
                throw ae;
            } catch (HistorySyncManager.SwitchAborted sa) {
                throw sa;
            } catch (Throwable perScope) {
                // ★ 协议 v4：单个 scope 失败不再中止整轮；失败 scope 保持 dirty，下轮重试。
                failedScopes.add(sc);
                LOG.e("[Sync] scope failed " + sc + " (others continue)", perScope);
            }
        }

        // ★ v4.1：有上传时用一次 Depth:1 批量刷新所有 scope 的 ETag（取代逐 scope recoverEtag）。
        if (anyUploaded) {
            try {
                o.requests++;
                java.util.Map<String, String> after = dav.propfindDepth1(dir);
                if (after != null) {
                    for (SyncScope sc : orderedScopes()) {
                        if (!enabled.isEnabled(sc)) {
                            continue;
                        }
                        String full = AccountPaths.scopeFile(accountName, sc);
                        int slash = full.lastIndexOf('/');
                        String name = slash >= 0 ? full.substring(slash + 1) : full;
                        String et = after.get(name);
                        if (et != null && et.length() > 0) {
                            st.setEtag(sc, et);
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.e("[Sync] refresh etags failed (keep old)", t);
            }
        }

        // ★ v4.1：更新设备水位（每 6 小时最多写一次 sync.meta，避免把 meta 变成写热点）。
        try {
            long oldSeen = metaLastSeen(meta, deviceId);
            meta.touch(deviceId, now);
            if (meta.reliable && (metaDirty || oldSeen <= 0L || now - oldSeen > 6L * 3600L * 1000L)) {
                saveMeta(dav, key, accountName, owner, meta, o, st);
            }
        } catch (Throwable t) {
            LOG.e("[Sync] save meta failed (ignored)", t);
        }

        // ★ r169：落库/裁剪真的改动了本机数据 ⇒ 主动通知界面刷新「最近观看 / 历史 / 收藏」。
        //   原来同步把云端新记录写回本机 DB 后不发任何事件，首页行只能等下一次
        //   onFragmentResume / 配置重载才更新，表现为「退出电视后手机打开要过好几秒才出现」。
        if (localChanged || pruned) {
            ConfigCenter.notifyHistoryRefresh();
            LOG.i("[Sync] notify history refresh changed=" + localChanged + " pruned=" + pruned);
        }

        // ★ 协议 v4：只清「本轮真正处理成功且此前是脏的」scope 的 dirty；
        //   失败/未启用的 scope 保持脏，下轮重试（旧实现会把它们一起清掉，导致丢更新）。
        boolean[] toClear = new boolean[SyncScope.COUNT];
        for (int i = 0; i < SyncScope.COUNT; i++) {
            boolean clear = dirtyBefore[i] && processedOk[i];
            SyncScope sc = SyncScope.values()[i];
            // ★ v4.1.3：只有「本机 DB 的最大 HLC 已经不超过 pushedHlc」才允许清 dirty。
            //   否则 applyLocal 之后、finish 之前的新写入会被本轮一起清掉，要等下次启动
            //   reconcilePushedHlc 才重新标脏。SETTINGS 没有 DB HLC，交给 settingsSynced。
            if (clear && sc != SyncScope.SETTINGS) {
                Hlc localMax = HistorySyncManager.localMaxHlc(sc);
                Hlc pushed = st.pushedHlc(sc);
                if (localMax.compareIgnoringDev(pushed) > 0) {
                    clear = false;
                }
            }
            toClear[i] = clear;
        }
        boolean allOk = failedScopes.isEmpty();
        // ★ P0-3/P0-7：只有「设置 scope 启用且本轮确实同步成功」才清 pendingPull；
        //   否则新设备首轮没学到账号档位（仍是默认 15），却会按 15 裁云端 50 条。
        boolean settingsSynced = enabled.settings && processedOk[SyncScope.SETTINGS.index];
        // ★ 只清「本轮真正上传成功」的那几类墓碑；未启用/失败的 scope 的墓碑必须保留。
        boolean clearRecords = toClear[SyncScope.HISTORY.index];
        boolean clearProgress = toClear[SyncScope.PROGRESS.index];
        boolean clearCollects = toClear[SyncScope.COLLECT.index];
        finish(st, now, toClear, clearRecords, clearProgress, clearCollects,
                tombWatermark, settingsSynced);

        o.ok = allOk;
        o.notModified = allNotModified;
        String tail = lastMerged == null ? "" :
                "（历史 " + lastMerged.records.size() + " / 进度 " + lastMerged.progress.size()
                        + " / 收藏 " + lastMerged.collects.size() + " / 设置 " + lastMerged.settings.size() + "）";
        // ★ v99-r14：修复过云端损坏文件时明确告知用户 —— 否则用户只会看到一次普通的
        //   「同步完成」，不会知道云端曾经坏过、以及自己的数据已被用来覆盖修复。
        String repairTip = repaired ? "（已修复损坏的云端数据文件）" : "";
        String failTip = failedScopes.isEmpty() ? "" : "（部分范围同步失败，将自动重试：" + failedScopes + "）";
        o.message = allNotModified ? ("同步完成（无变更）" + repairTip + failTip)
                : ("同步完成" + tail + repairTip + failTip);
        LOG.i("[Sync] done owner=" + owner + " req=" + o.requests
                + " down=" + o.bytesDown + " up=" + o.bytesUp + " uploaded=" + anyUploaded
                + " repaired=" + repaired);
        return o;
    }

    // ===================== v4.1：sync.meta（设备水位）=====================

    /**
     * 读取 sync.meta。任何失败（不存在/损坏/网络）都降级为「全新 meta」，绝不让 meta 阻断同步。
     * 存在时用 {@link SyncState#metaEtag} 做条件请求，未变化时服务端返回 304、零下载。
     */
    private static SyncMeta loadMeta(WebDavClient dav, byte[] key, String accountName,
                                     String owner, Outcome o, SyncState st,
                                     java.util.Map<String, String> dirEntries) {
        String path = AccountPaths.metaFile(accountName);
        try {
            // 目录清单里没有 sync.meta ⇒ 不存在，零请求返回空 meta（首次/老账号不会多一次 404）。
            String remoteEtag = dirEntries == null ? null : dirEntries.get("sync.meta");
            if (remoteEtag == null || remoteEtag.isEmpty()) {
                return new SyncMeta(owner);
            }
            // ETag 未变 ⇒ 用本机缓存的正文，避免重复下载。
            if (st.metaJson != null && st.metaJson.length() > 0
                    && st.metaEtag != null && st.metaEtag.equals(remoteEtag)) {
                SyncMeta cached = HistorySyncManager.GSON.fromJson(st.metaJson, SyncMeta.class);
                if (cached != null) {
                    cached.owner = owner;
                    return cached;
                }
            }
            String[] etagOut = new String[1];
            o.requests++;
            byte[] raw = dav.get(path, etagOut);
            if (etagOut[0] != null) {
                st.metaEtag = etagOut[0];
            }
            if (raw == null || raw.length == 0) {
                return new SyncMeta(owner);
            }
            String json = new String(Crypto.decrypt(key, raw), "UTF-8");
            st.metaJson = json;
            SyncMeta m = HistorySyncManager.GSON.fromJson(json, SyncMeta.class);
            if (m == null) {
                m = new SyncMeta(owner);
            }
            m.owner = owner;
            return m;
        } catch (Throwable t) {
            LOG.e("[Sync] load meta failed (use empty)", t);
            SyncMeta bad = new SyncMeta(owner);
            bad.reliable = false;
            return bad;
        }
    }

    private static void saveMeta(WebDavClient dav, byte[] key, String accountName,
                                 String owner, SyncMeta meta, Outcome o, SyncState st) {
        try {
            byte[] out = Crypto.encrypt(key,
                    HistorySyncManager.GSON.toJson(meta).getBytes("UTF-8"));
            o.requests++;
            String et = dav.put(AccountPaths.metaFile(accountName), out,
                    (st.metaEtag == null || st.metaEtag.length() == 0) ? null : st.metaEtag);
            if (et != null && et.length() > 0) {
                st.metaEtag = et;
            }
            st.metaJson = HistorySyncManager.GSON.toJson(meta);
        } catch (Throwable t) {
            LOG.e("[Sync] save meta failed (ignored)", t);
        }
    }

    private static long metaLastSeen(SyncMeta meta, String deviceId) {
        SyncMeta.Device d = meta.device(deviceId);
        return d == null ? 0L : d.lastSeenAt;
    }

    /**
     * 把旧的单文件 {@code data.bin} 一次性迁移成 4 个 scope 文件（schema 2 → 3）。
     *
     * <p>用户已确认「一次性切换」：迁移后不再读写 data.bin，旧版本 App 同步会拿不到数据
     * （所以要求所有设备同时升级）。</p>
     *
     * <p>迁移过程幂等：中途失败下次会重来（{@code putIfAbsent} 只创建不存在的 split 文件，
     * 已有云端版本会被完整保留）。迁移成功后才置 {@code splitMigrated}，
     * 随后删除旧 {@code data.bin}，避免新装/清数据设备再次用它覆盖线上数据（P0-2）。</p>
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
            try {
                // ★ 协议 v4：只有 split 文件不存在时才创建，绝不覆盖已有云端数据。
                //   多台设备同时升级时，第一个创建者胜出，其余走正常合并。
                String newEtag = dav.putIfAbsent(AccountPaths.scopeFile(accountName, sc), out);
                String etM = recoverEtag(dav, AccountPaths.scopeFile(accountName, sc), newEtag, o);
                if (etM != null) {
                    st.setEtag(sc, etM);
                }
            } catch (WebDavClient.ConflictException exists) {
                LOG.i("[Sync] split file already exists, keep cloud version: " + sc);
            }
        }
        st.splitMigrated = true;
        if (all.epoch > st.lastEpoch) {
            st.lastEpoch = all.epoch;
        }
        st.save();
        // ★ 迁移后删除旧 data.bin：否则新装/清数据设备（splitMigrated=false）会再次
        //   读到陈旧整包并覆盖线上 split 文件（P0-2）。删除失败不影响迁移结果。
        try {
            dav.delete(AccountPaths.data(accountName));
        } catch (Throwable ignore) {
            LOG.i("[Sync] legacy data.bin cleanup skipped: " + ignore.getMessage());
        }
        LOG.i("[Sync] migrated data.bin -> 4 scope files owner=" + st.owner
                + " epoch=" + all.epoch);
    }

    /**
     * ★ v99-r21：PUT / GET 之后确保 ETag 不丢。
     *
     * <p><b>为什么要补这一手</b>：坚果云等 WebDAV 服务端的 <b>PUT 响应头不带 ETag</b>
     * ⇒ {@code newEtag == null} ⇒ 旧代码直接 {@code st.setEtag(sc, null)} 把本地 ETag
     * 清空 ⇒ 之后的条件请求不再带 {@code If-None-Match} ⇒ 每次都 200 <b>全量下载</b>。</p>
     *
     * <p>全量下载本身要流量，更麻烦的是它每次都进入「合并后与云端比对」分支：只要本地
     * 进度有一丁点漂移（周期落盘刷新了 updateTime/position）就判定 same=false 并上传，
     * 而上传又把 ETag 清空一次 ⇒ 形成 {@code 每 5 分钟传一次 progress.bin} 的自锁循环
     * （上传使 active=true ⇒ 自适应档位被重置回 5 分钟档，永远升不上去）。
     * 实测证据：最近 5 份日志里 {@code skip=304} 全部为 0，只有 {@code skip=same down=NNNN}。</p>
     *
     * <p>这里在响应没给 ETag 时补一次 PROPFIND（无 body，1 个请求）把真实 ETag 取回来；
     * 仍然取不到就返回 null，由调用方<b>保留旧值</b>，绝不用 null 覆盖。</p>
     */
    private static String recoverEtag(WebDavClient dav, String path, String etag, Outcome o) {
        if (etag != null && etag.length() > 0) {
            return etag;
        }
        try {
            o.requests++;
            String again = dav.getEtag(path);
            if (again != null && again.length() > 0) {
                LOG.i("[Sync] etag recovered via PROPFIND " + shortName(path));
                return again;
            }
        } catch (Throwable t) {
            LOG.i("[Sync] etag recover failed " + t.getClass().getSimpleName());
        }
        LOG.i("[Sync] etag missing " + shortName(path) + " (keep old)");
        return null;
    }

    private static String shortName(String path) {
        if (path == null) {
            return "null";
        }
        int i = path.lastIndexOf('/');
        return i >= 0 && i + 1 < path.length() ? path.substring(i + 1) : path;
    }

    /** 解密 + 反序列化；空数据返回空包（不是 null，后续合并要能直接用）。 */    private static SyncPayload decode(byte[] key, byte[] raw, String owner) throws Exception {
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
        // ★ 协议 v4：版本闸门。读到比本机更新的协议时拒绝写入，避免旧客户端
        //   用自己理解的字段覆盖掉新客户端写入的 HLC / 删除信息（降级即丢数据）。
        if (p.schema > SyncPayload.SCHEMA) {
            throw new IOException("云端同步数据版本过新（schema=" + p.schema
                    + "，本机支持 " + SyncPayload.SCHEMA + "），请升级 App 后再同步");
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
     * <p>语义：云端的 <b>3 个数据文件</b>（history/progress/collect）写成「空 + epoch+1」，
     * <b>settings 不在清空范围</b>（用户拍板）；同时清空本机三张数据表并更新本地 epoch。</p>
     *
     * <p><b>为什么必须清本机</b>：旧实现只清云端、并把 {@code lastEpoch} 更新成新值 ——
     * 结果是本机之后再也不会触发 reset（{@code remote.epoch > lastEpoch} 不成立），
     * 而下一次同步会把本机数据原样合并上传，<b>刚清空的云端立刻被恢复</b>。
     * 所以这里清完云端就顺手清本机，语义才自洽。</p>
     */
    public static Outcome clearCloud(String owner, byte[] key, int gen) {
        try {
            SyncState st = SyncState.load(owner);
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
            final long now = TrustedClock.nowPhysical();

            // ★ 协议 v4：清空云端只清「数据」三个 scope；settings 不在清空范围内（用户拍板）。
            SyncScope[] targets = new SyncScope[]{
                    SyncScope.HISTORY, SyncScope.PROGRESS, SyncScope.COLLECT};
            int okCount = 0;
            for (SyncScope sc : targets) {
                SyncPayload empty = new SyncPayload();
                empty.owner = owner;
                empty.epoch = epoch;
                empty.updatedAt = now;
                empty.dev = deviceId;
                byte[] out = Crypto.encrypt(key,
                        HistorySyncManager.GSON.toJson(empty.forScope(sc)).getBytes("UTF-8"));
                try {
                    String newEtag = dav.put(AccountPaths.scopeFile(accountName, sc), out, null);
                    String etC = recoverEtag(dav, AccountPaths.scopeFile(accountName, sc), newEtag, o);
                    if (etC != null) {
                        st.setEtag(sc, etC);
                    }
                    okCount++;
                } catch (Throwable t) {
                    LOG.e("[Sync] clearCloud write failed scope=" + sc, t);
                }
            }
            if (okCount != targets.length) {
                // 有文件没写成功：不清本机、不推进 epoch，避免半清空（下次重试）
                return fail("清空失败：部分云端文件未能写入（" + okCount + "/" + targets.length + "）");
            }

            // 清本机：否则下次同步会把本机数据又合并上传，等于没清。
            // ★ 清空范围=三个数据表（与云端一致），不依赖「scope 开关」，也不动 settings。
            SyncScopes dataScopes = new SyncScopes();
            dataScopes.history = true;
            dataScopes.progress = true;
            dataScopes.collect = true;
            dataScopes.settings = false;
            HistorySyncManager.wipeLocal(dataScopes);
            // ★ r169：本机三表已清空，主动通知界面刷新（首页最近观看 / 历史 / 收藏）。
            ConfigCenter.notifyHistoryRefresh();
            // ★ 协议 v4：清空后旧删除墓碑已无意义，一并清掉（避免后续压制新数据）。
            SyncTombstones.clear(now);

            st.lastEpoch = epoch;
            st.lastAt = now;
            // 只清三个数据 scope 的脏标记；用户未上传的设置改动必须保留。
            boolean settingsDirty = st.dirty[SyncScope.SETTINGS.index];
            st.clearDirty();
            st.dirty[SyncScope.SETTINGS.index] = settingsDirty;
            st.clearFailure();
            st.save();

            Outcome r = new Outcome();
            r.ok = true;
            r.requests = o.requests + targets.length;
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
        return runSafe(owner, key, gen, forceUpload, false);
    }

    /** ★ v4.2.2（B）：带 {@code lightFirstRound} 的版本，见 {@link #run(String, byte[], int, boolean, boolean)}。 */
    public static Outcome runSafe(String owner, byte[] key, int gen, boolean forceUpload,
                                  boolean lightFirstRound) {
        try {
            return run(owner, key, gen, forceUpload, lightFirstRound);
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
    private static void finish(SyncState st, long now, boolean[] dirtyBefore,
                               boolean clearRecords, boolean clearProgress, boolean clearCollects,
                               Hlc tombWatermark, boolean settingsSynced) {
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
            // ★ v4.1.3：把本轮推进的 pushedHlc/meta 合并回最新状态。
            //   原来 st 只是局部快照，bumpPushedHlc/saveMeta 的结果会随 finish 丢弃，
            //   导致下轮误判「未推送」或重复 GET sync.meta。
            if (st.pushedHlcPhysical != null && st.pushedHlcCounter != null) {
                for (int i = 0; i < SyncScope.COUNT
                        && i < st.pushedHlcPhysical.length
                        && i < st.pushedHlcCounter.length; i++) {
                    cur.bumpPushedHlc(SyncScope.values()[i],
                            st.pushedHlcPhysical[i], st.pushedHlcCounter[i]);
                }
            }
            if (st.metaEtag != null && st.metaEtag.length() > 0) {
                cur.metaEtag = st.metaEtag;
            }
            if (st.metaJson != null && st.metaJson.length() > 0) {
                cur.metaJson = st.metaJson;
            }
            cur.clearFailure();
            cur.save();
        }
        // ★ 协议 v4：只有「所有启用 scope 都成功」时才清墓碑；任一 scope 失败就保留，
        //   否则该 scope 的删除墓碑会被清掉，重新启用后云端旧数据又"复活"。
        // ★ 协议 v4：设置真正同步成功后才解除「只拉不推」；失败则保持，下轮继续以云端为准。
        if (settingsSynced) {
            SettingsProfile.setPendingPull(st.owner, false);
        }
        if (clearRecords || clearProgress || clearCollects) {
            SyncTombstones.clear(tombWatermark, clearRecords, clearProgress, clearCollects);
        }
    }

    /** ★ 协议 v4：设置先同步，其余 scope 随后 —— 保证 HISTORY 裁剪用的是账号级档位。 */
    private static SyncScope[] orderedScopes() {
        return new SyncScope[]{SyncScope.SETTINGS, SyncScope.HISTORY,
                SyncScope.PROGRESS, SyncScope.COLLECT};
    }
}
