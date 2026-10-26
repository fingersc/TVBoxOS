package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.sync.HistorySyncManager;
import com.github.tvbox.osc.sync.SyncScope;
import com.github.tvbox.osc.sync.SyncMerge;
import com.github.tvbox.osc.sync.SyncPayload;
import com.github.tvbox.osc.sync.SyncTombstones;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 播放进度的列存储门面：代替原 CacheManager + MD5(拼接串) 的进度存储。
 * 所有字段经 null 规范化（null -> ""），保证主键稳定；规范化只在这里做一次。
 */
public class PlayProgressManager {

    private static String s(String v) {
        return v == null ? "" : v;
    }

    /** 保存/覆盖某一集的进度；position <= 0 等价于清除该集记忆（对应完成播放写 0 的旧语义） */
    public static void save(String sourceKey, String vodId, String flag, int playIndex, String epName, long position) {
        save(sourceKey, vodId, flag, playIndex, epName, position, true);
    }

    /**
     * 保存/覆盖某一集的进度。
     *
     * <p><b>★ v99-r16：新增 {@code markDirty} 参数，用于把「写本地库」与「触发同步」解耦。</b></p>
     *
     * <p><b>为什么必须解耦</b>：v99-r14 加了播放中的周期落盘（30 秒一次，防进程被强杀丢进度），
     * 但落盘走的是本方法，而本方法每次都 {@code markDirty(PROGRESS)} ⇒
     * 每 60 秒防抖窗口就触发一次同步，每次都要把<b>整个 progress.bin</b>
     * 完整 GET + PUT（实测 30~50 部片约 33~55KB）。</p>
     *
     * <p>实测流量（用户坚果云反馈「一分钟同步一次」）：</p>
     * <ul>
     *   <li>每天看 2 小时 ⇒ 月上传约 <b>144MB</b></li>
     *   <li>盒子 24h 挂机暂停 ⇒ 月上传约 <b>1.7GB</b>，<b>超出坚果云免费版 1GB/月限额</b></li>
     * </ul>
     *
     * <p><b>周期落盘传 {@code false}</b>：它的目的是「强杀不丢进度」，而<b>本地 Room 写了
     * 就已经达成</b>，不需要传云端。不标脏 ⇒ 不触发同步 ⇒ 流量回到加周期落盘之前的水平。
     * 退出播放页仍由 {@code PlayFragment.onDestroyView} 的
     * {@code saveCurrentProgressNow()} + {@code onPlaybackExit()} 无条件落盘 + 标脏 + 同步兜底。</p>
     *
     * <p><b>删除路径（{@code position <= 0}）不受该参数影响，一律标脏</b>：
     * 删除是用户可见的语义变化，且周期落盘的调用方都有 {@code pos <= 0} 守卫，永远不会走到删除分支。</p>
     *
     * @param markDirty false = 只写本地库，不触发同步（仅周期落盘用）
     */
    public static void save(String sourceKey, String vodId, String flag, int playIndex,
                            String epName, long position, boolean markDirty) {
        try {
            if (TextUtils.isEmpty(vodId) || TextUtils.isEmpty(epName)) {
                return;
            }
            PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
            if (position <= 0) {
                dao.delete(s(sourceKey), s(vodId), s(flag), playIndex, s(epName));
                // 删除必须标脏：否则别的设备永远不知道这一集被清除了
                HistorySyncManager.markDirty(SyncScope.PROGRESS);
                return;
            }
            PlayProgress p = new PlayProgress();
            p.sourceKey = s(sourceKey);
            p.vodId = s(vodId);
            p.flag = s(flag);
            p.playIndex = playIndex;
            p.epName = s(epName);
            p.position = position;
            p.updateTime = System.currentTimeMillis();
            dao.save(p);
            // ★ v99-r10/r11：每部片只保留最近 KEEP_PROGRESS_EPISODES 条进度记录（不分线路）。
            //   原来是一集一行、永不上限：50 集 × 20 部 = 1000 行（同集换线路还会再翻倍），
            //   而同步是整包上传 progress.bin ⇒ 每次改善进度都要传上百 KB。
            trimByVod(p.sourceKey, p.vodId, HistorySyncManager.KEEP_PROGRESS_EPISODES);
            // v98 P2：只看剧不动历史/收藏/设置 —— 只脏「进度」
            if (markDirty) {
                HistorySyncManager.markDirty(SyncScope.PROGRESS);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * ★ v99-r10/r11：每部片只保留最近 {@link HistorySyncManager#KEEP_PROGRESS_EPISODES} 条<b>进度记录</b>。
     *
     * <p><b>按「记录」计数，不按「集」聚合</b>：同一部片下各线路（flag）的记录合起来
     * 只留最新的 N 条。理由是「切换线路」与「切源」目的相同（都是为了更快的播放），
     * 换线路时旧线路的记录会整批迁到新线路并丢弃（{@link #migrateByFlag}），
     * 稳态下每片就是「N 集 × 当前那一条线路」。</p>
     *
     * <p>规则实现在 {@link SyncMerge#keepRecent}（纯函数），本地落库与云端同步包共用同一套。</p>
     *
     * @return 实际删除的行数
     */
    public static int trimByVod(String sourceKey, String vodId, int keep) {
        try {
            if (keep <= 0 || TextUtils.isEmpty(vodId)) {
                return 0;
            }
            PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
            final String sk = s(sourceKey);
            final String vid = s(vodId);
            List<PlayProgress> rows = dao.getByVod(sk, vid);
            if (rows == null || rows.size() <= keep) {
                return 0;
            }
            // 转成同步包条目，交给 SyncMerge.keepRecent 算「保留哪几条记录」——
            // 本地落库与云端同步包共用同一套规则，避免两边裁出不同结果。
            List<SyncPayload.ProgressItem> items = new ArrayList<>(rows.size());
            for (PlayProgress r : rows) {
                if (r == null) {
                    continue;
                }
                items.add(toItem(r));
            }
            List<SyncPayload.ProgressItem> kept = SyncMerge.keepRecent(items, keep);
            if (kept == null || kept.size() >= items.size()) {
                return 0;
            }
            Set<String> keepKeys = new HashSet<>();
            for (SyncPayload.ProgressItem it : kept) {
                keepKeys.add(rowKey(it.flag, it.playIndex, it.epName));
            }
            int removed = 0;
            for (PlayProgress r : rows) {
                if (r == null || keepKeys.contains(rowKey(r.flag, r.playIndex, r.epName))) {
                    continue;
                }
                removed += dao.delete(r.sourceKey, r.vodId, r.flag, r.playIndex, r.epName);
            }
            return removed;
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }

    private static String rowKey(String flag, int playIndex, String epName) {
        return s(flag) + "\u0001" + playIndex + "\u0001" + s(epName);
    }

    private static SyncPayload.ProgressItem toItem(PlayProgress r) {
        SyncPayload.ProgressItem it = new SyncPayload.ProgressItem();
        it.sourceKey = r.sourceKey;
        it.vodId = r.vodId;
        it.flag = r.flag;
        it.playIndex = r.playIndex;
        it.epName = r.epName;
        it.position = r.position;
        it.updateTime = r.updateTime;
        return it;
    }

    /**
     * ★ v99-r11：同站「换线路」时把旧线路<b>最近 {@code keep} 条</b>进度迁到新线路，然后旧线路整体抛弃。
     *
     * <p><b>为什么换线路等于换源</b>：两者目的相同（都是为了更快的播放），
     * 所以旧线路的记录留着只会占名额——裁剪是「每片最多 N 条记录」（不分线路），
     * 旧线路的记录会白白吃掉一半名额。</p>
     *
     * <p><b>三条规则（用户确认）</b>：</p>
     * <ol>
     *   <li>只迁旧线路<b>最近 {@code keep} 条</b>（按 updateTime 降序），不是整片所有集；</li>
     *   <li>新线路<b>已有该集 ⇒ 覆盖</b>（用旧线路的播放位置覆盖新线路的）；</li>
     *   <li>迁完旧线路整条删除，并登记墓碑（否则云端那份会被 LWW 合并"复活"）。</li>
     * </ol>
     *
     * <p><b>对称性</b>：A→B 后 A 的行被清空、B 持有这 N 条；再 B→A 时旧=B 新=A，
     * 于是 B 的这 N 条又整批搬回 A（A 此刻是空的）——来回切换不丢数据、位置保持不变。</p>
     *
     * <p><b>集名对齐</b>：新线路集名写法可能不同（{@code 第01集} vs {@code 01}），
     * 用 {@link com.github.tvbox.osc.util.EpisodeNameMatcher} 跨写法对齐，
     * 对齐后以<b>新线路的集名与集序号</b>落库（对齐不到才退回旧值）。</p>
     *
     * @return 实际迁移的条数
     */
    public static int migrateByFlag(String sourceKey, String vodId,
                                    String fromFlag, String toFlag,
                                    java.util.List<String> toEpNames) {
        try {
            final String sk = s(sourceKey);
            final String vid = s(vodId);
            final String ff = s(fromFlag);
            final String tf = s(toFlag);
            if (TextUtils.isEmpty(vid) || TextUtils.isEmpty(ff) || TextUtils.isEmpty(tf)
                    || TextUtils.equals(ff, tf)) {
                return 0;
            }
            final int keep = HistorySyncManager.KEEP_PROGRESS_EPISODES;
            PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
            List<PlayProgress> rows = dao.getByVodFlag(sk, vid, ff);
            if (rows == null || rows.isEmpty()) {
                return 0;
            }
            // 1) 候选：只取有播放位置的，并按 updateTime 降序
            List<PlayProgress> cand = new ArrayList<>();
            for (PlayProgress row : rows) {
                if (row != null && row.position > 0) {
                    cand.add(row);
                }
            }
            if (cand.isEmpty()) {
                dao.deleteByVodFlag(sk, vid, ff);
                return 0;
            }
            Collections.sort(cand, new java.util.Comparator<PlayProgress>() {
                @Override
                public int compare(PlayProgress a, PlayProgress b) {
                    if (a.updateTime == b.updateTime) {
                        return 0;
                    }
                    return a.updateTime > b.updateTime ? -1 : 1;
                }
            });
            if (cand.size() > keep) {
                cand = new ArrayList<>(cand.subList(0, keep));
            }
            // 2) 新线路现有行（用于「覆盖」：同集序号的老行要删掉，否则会留下两条）
            List<PlayProgress> toRows = dao.getByVodFlag(sk, vid, tf);
            // 3) 新线路集名 → 序号
            java.util.Map<String, Integer> toIdx = new HashMap<>();
            if (toEpNames != null) {
                for (int i = 0; i < toEpNames.size(); i++) {
                    String nm = toEpNames.get(i);
                    if (!TextUtils.isEmpty(nm) && !toIdx.containsKey(nm)) {
                        toIdx.put(nm, i);
                    }
                }
            }
            final long now = System.currentTimeMillis();
            int moved = 0;
            int ordinal = 0;
            for (PlayProgress row : cand) {
                // 3.1 目标集（序号 + 名称）：优先完全一致，其次跨写法对齐
                int idx = toIdx.containsKey(row.epName) ? toIdx.get(row.epName) : -1;
                if (idx < 0 && toEpNames != null && !toEpNames.isEmpty()) {
                    idx = com.github.tvbox.osc.util.EpisodeNameMatcher.findIndex(row.epName, toEpNames);
                }
                String targetEp = (idx >= 0) ? toEpNames.get(idx) : row.epName;
                int targetIndex = (idx >= 0) ? idx : row.playIndex;
                // 3.2 覆盖：删掉新线路同集序号的旧行（集名写法不同时是另一行）
                if (toRows != null) {
                    for (PlayProgress tr : toRows) {
                        if (tr != null && tr.playIndex == targetIndex
                                && !TextUtils.equals(tr.epName, targetEp)) {
                            dao.delete(sk, vid, tf, tr.playIndex, tr.epName);
                        }
                    }
                }
                // 3.3 写入新线路（updateTime 用当前时间 ⇒ 本地/云端 LWW 都以迁移结果为准）
                PlayProgress np = new PlayProgress();
                np.sourceKey = sk;
                np.vodId = vid;
                np.flag = tf;
                np.playIndex = targetIndex;
                np.epName = targetEp;
                np.position = row.position;
                np.updateTime = now + ordinal;
                dao.save(np);
                ordinal++;
                moved++;
            }
            // 4) 旧线路整体抛弃 + 登记墓碑（防止云端那份被 LWW 合并复活）
            for (PlayProgress row : rows) {
                if (row != null) {
                    SyncTombstones.addProgress(sk, vid, ff, row.playIndex, row.epName);
                }
            }
            dao.deleteByVodFlag(sk, vid, ff);
            // 5) 按「每片最多 N 条记录」裁剪一次（迁移来的这批时间戳最新，优先保留）
            trimByVod(sk, vid, keep);
            HistorySyncManager.markDirty(SyncScope.PROGRESS);
            return moved;
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }

    /** 读取某一集的进度；无记录返回 0 */
    public static long get(String sourceKey, String vodId, String flag, int playIndex, String epName) {
        try {
            PlayProgress p = AppDataManager.get().getPlayProgressDao()
                    .get(s(sourceKey), s(vodId), s(flag), playIndex, s(epName));
            return p == null ? 0 : p.position;
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }

    public static void delete(String sourceKey, String vodId, String flag, int playIndex, String epName) {
        try {
            AppDataManager.get().getPlayProgressDao().delete(s(sourceKey), s(vodId), s(flag), playIndex, s(epName));
            SyncTombstones.addProgress(sourceKey, vodId, flag, playIndex, epName);
            HistorySyncManager.markDirty(SyncScope.PROGRESS);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 把某部影片在「旧源」下的<b>全部集</b>进度整体搬到「新源」，然后清除旧源的行。
     *
     * <p><b>为什么需要：</b>换源时旧源的历史记录会被删除（同名合并 / 超限裁剪），
     * 旧源名下的进度行随即变成读不到的孤儿。而详情回调里的迁移只搬"当前正在播的那一集"，
     * 其余各集的播放位置与最后观看时间会随旧源行一起被清掉——表现为换源后
     * 「以前看过的集，时间记忆全没了」。</p>
     *
     * <p>搬移规则：
     * <ol>
     *   <li>集名在新源里存在（{@code toEpNames} 精确包含）→ 按原样搬到同一集名；</li>
     *   <li>集名对新源不适用（跨源命名不同，如「第5集」↔「20260904」）→ 用
     *       {@link com.github.tvbox.osc.util.EpisodeNameMatcher#findIndex(String, java.util.List)}
     *       在新源集名里找同集，搬到匹配到的集名上；</li>
     *   <li>新源该集已有进度 → <b>不覆盖</b>，保留新源自己记的值；</li>
     *   <li>实在匹配不上 → 该行随旧源一起清除（留着也永远读不到）。</li>
     * </ol></p>
     *
     * <p><b>★ v99-r77：写入坐标必须换算到「新源的坐标系」。</b>
     * 旧实现把旧源的 {@code flag}（线路名）与 {@code playIndex}（集下标）原样带进新源，
     * 而读侧（{@code PlayFragment.getSavedProgress}）用的是
     * <b>新源自己的</b> {@code playFlag} 与 {@code playIndex} —— 两边坐标系不同，
     * 搬过去的行<b>永远读不到</b>。
     * 真机实证（2026-10-26）：同一部片连切 4 站，{@code migrateVod} 的 moved 逐跳递增
     * {@code 1 → 2 → 2 → 9 → 10}，库里堆出 9 条坐标错乱的行；
     * 而 {@code trimByVod} 按 {@code updateTime} 只留 10 条 ⇒ <b>有效进度被垃圾行挤掉</b>，
     * 表现为「切源后进度归零」。</p>
     *
     * <p>本版：写入坐标取自 {@code toCoords}（新源的「集名 → (线路, 线路内下标)」），
     * 与读侧完全同源；匹配不到坐标的行<b>不写</b>（而不是搬成一条读不到的错行）。</p>
     *
     * <p><b>★ v99-r77：清空旧源时逐行登记进度墓碑。</b>
     * 旧实现直接 {@code dao.deleteByVod}，云端那份旧行还在 ⇒ 下次同步 LWW 合并把它<b>复活</b>回本机
     * ⇒ 旧源名下进度越滚越多（真机：同名旧源反复被扫出 9 行）。
     * 与 {@link #migrateByFlag} 的既有做法对齐：先 {@code SyncTombstones.addProgress} 再删。</p>
     *
     * <p><b>★ v99-r77：{@code toCoords} 为空 ⇒ 整体不动。</b>
     * 拿不到新源坐标系时若照旧搬移，只会产出读不到的垃圾行并挤掉新源的真实进度；
     * 此时<b>既不搬也不删</b>，旧源的行留在原处（用户日后从搜索重新点进该片时进度仍在）。</p>
     *
     * @param toEpNames 新源的全部集名（用于跨源集名匹配）；传 null 则只做集名精确搬移
     * @param toCoords  新源「集名 → {@link EpCoord}」坐标表；空 ⇒ 不做任何搬移
     * @return 实际搬移的行数
     */
    public static int migrateByVod(String fromSourceKey, String fromVodId,
                                   String toSourceKey, String toVodId,
                                   java.util.List<String> toEpNames,
                                   java.util.Map<String, EpCoord> toCoords) {
        try {
            if (TextUtils.isEmpty(fromSourceKey) || TextUtils.isEmpty(fromVodId)
                    || TextUtils.isEmpty(toSourceKey) || TextUtils.isEmpty(toVodId)) {
                return 0;
            }
            if (TextUtils.equals(fromSourceKey, toSourceKey)
                    && TextUtils.equals(fromVodId, toVodId)) {
                return 0;
            }
            // ★ v99-r77：没有新源坐标系 ⇒ 不搬不删（详见方法注释）
            if (toCoords == null || toCoords.isEmpty()) {
                return 0;
            }
            PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
            java.util.List<PlayProgress> rows = dao.getByVod(s(fromSourceKey), s(fromVodId));
            if (rows == null || rows.isEmpty()) {
                return 0;
            }
            int moved = 0;
            for (PlayProgress row : rows) {
                if (row == null || row.position <= 0) {
                    continue;
                }
                String targetEp = row.epName;
                boolean exact = toEpNames != null && toEpNames.contains(targetEp);
                if (!exact && toEpNames != null && !toEpNames.isEmpty()) {
                    int idx = com.github.tvbox.osc.util.EpisodeNameMatcher.findIndex(targetEp, toEpNames);
                    if (idx >= 0) {
                        targetEp = toEpNames.get(idx);
                    }
                }
                // ★ v99-r77：坐标取自新源（线路 + 该线路内下标），与读侧同源
                EpCoord coord = toCoords.get(targetEp);
                if (coord == null) {
                    // 新源没有这一集 ⇒ 无处可落 ⇒ 不写（写了也读不到）
                    continue;
                }
                // 新源该集已有记忆则以新源为准，不覆盖
                if (get(toSourceKey, toVodId, coord.flag, coord.index, coord.epName) > 0) {
                    continue;
                }
                save(toSourceKey, toVodId, coord.flag, coord.index, coord.epName, row.position);
                moved++;
            }
            // ★ v99-r75：旧源该片的进度<b>整体清空</b> —— 这正是既有口径
            //   「同片只保留最新 10 集记录，并且都随着迁移到新源里去」：
            //   进度跟着"当前所在的源"走，旧源不留副本 ⇒ 同一部片不会在多个源各留一份。
            // ★ v99-r77：删之前**逐行登记进度墓碑**，否则云端那份旧行会被 LWW 合并复活
            //   （真机：旧源名下进度反复被扫出 9 行）。
            for (PlayProgress row : rows) {
                if (row != null) {
                    SyncTombstones.addProgress(s(fromSourceKey), s(fromVodId),
                            row.flag, row.playIndex, row.epName);
                }
            }
            dao.deleteByVod(s(fromSourceKey), s(fromVodId));
            HistorySyncManager.markDirty(SyncScope.PROGRESS);
            return moved;
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }

    /**
     * ★ v99-r77：某个源里某部影片的<b>坐标</b>（线路名 + 该线路内的集下标）。
     *
     * <p>播放进度的主键是 {@code (源, 片, 线路, 集下标, 集名)}，
     * 其中「线路」与「集下标」都是<b>相对于该源该线路</b>的本地坐标 ——
     * 跨源搬移时必须换算，否则读侧（用新源自己的 {@code playFlag}/{@code playIndex}）
     * 永远查不到搬过去的行。</p>
     */
    public static final class EpCoord {
        /** 线路名（源内的多线路标识）。 */
        public final String flag;
        /** 该集在这条线路里的下标。 */
        public final int index;
        /** 该集在线路里的集名（与 index 对应，作为主键的一部分）。 */
        public final String epName;

        public EpCoord(String flag, int index, String epName) {
            this.flag = flag == null ? "" : flag;
            this.index = index;
            this.epName = epName == null ? "" : epName;
        }
    }

    /**
     * ★ v99-r77：建立某部影片的「集名 → {@link EpCoord}」坐标表。
     *
     * <p>同一个集名可能出现在多条线路里（各线路集序不同）；
     * 优先取<b>当前播放线路</b>（{@code preferFlag}）上的位置 ——
     * 读侧正是用它来查进度的。</p>
     *
     * @param vodInfo     影片信息（须带 {@code seriesMap}）
     * @param preferFlag  优先采用的线路名（通常是新源的 {@code playFlag}）
     */
    public static java.util.Map<String, EpCoord> collectEpisodeCoords(
            com.github.tvbox.osc.bean.VodInfo vodInfo, String preferFlag) {
        java.util.Map<String, EpCoord> out = new java.util.LinkedHashMap<>();
        if (vodInfo == null || vodInfo.seriesMap == null) {
            return out;
        }
        try {
            // 1) 先放当前播放线路（读侧按它查进度）
            if (!TextUtils.isEmpty(preferFlag)) {
                putCoords(out, preferFlag, vodInfo.seriesMap.get(preferFlag));
            }
            // 2) 其余线路补齐（该集只在别的线路存在时也能落位）
            for (java.util.Map.Entry<String, java.util.List<com.github.tvbox.osc.bean.VodInfo.VodSeries>> entry
                    : vodInfo.seriesMap.entrySet()) {
                if (TextUtils.equals(entry.getKey(), preferFlag)) {
                    continue;
                }
                putCoords(out, entry.getKey(), entry.getValue());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void putCoords(java.util.Map<String, EpCoord> out, String flag,
                                  java.util.List<com.github.tvbox.osc.bean.VodInfo.VodSeries> list) {
        if (TextUtils.isEmpty(flag) || list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            com.github.tvbox.osc.bean.VodInfo.VodSeries s = list.get(i);
            if (s == null || TextUtils.isEmpty(s.name)) {
                continue;
            }
            String n = s.name.trim();
            if (!TextUtils.isEmpty(n) && !out.containsKey(n)) {
                out.put(n, new EpCoord(flag, i, n));
            }
        }
    }

    /**
     * ★ v99-r75：收集一部影片在<b>所有线路</b>下的集名（供跨源集名匹配）。
     *
     * <p>原先是 {@code RoomDataManger} 里的私有方法；现在提到这里做<b>单一实现</b> ——
     * DetailActivity 的迁移（{@code migratePlaybackTimeFromOldSource}）也需要它，
     * 两边各留一份必然分叉。</p>
     */
    public static java.util.List<String> collectAllEpisodeNames(com.github.tvbox.osc.bean.VodInfo vodInfo) {
        java.util.List<String> out = new ArrayList<>();
        if (vodInfo == null || vodInfo.seriesMap == null) {
            return out;
        }
        try {
            for (java.util.Map.Entry<String, java.util.List<com.github.tvbox.osc.bean.VodInfo.VodSeries>> entry
                    : vodInfo.seriesMap.entrySet()) {
                java.util.List<com.github.tvbox.osc.bean.VodInfo.VodSeries> list = entry.getValue();
                if (list == null) continue;
                for (com.github.tvbox.osc.bean.VodInfo.VodSeries series : list) {
                    if (series == null || TextUtils.isEmpty(series.name)) continue;
                    String n = series.name.trim();
                    if (!TextUtils.isEmpty(n) && !out.contains(n)) {
                        out.add(n);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 删除整部影片所有集的进度（配合删除历史） */
    public static void deleteByVod(String sourceKey, String vodId) {
        try {
            AppDataManager.get().getPlayProgressDao().deleteByVod(s(sourceKey), s(vodId));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public static void deleteAll() {
        try {
            AppDataManager.get().getPlayProgressDao().deleteAll();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 清理"孤儿"进度：所属影片已不在观看历史（vodRecord 表）中，因此永远无法再被读到。
     * <p>产生途径有三：历史条数超上限被 {@code VodRecordDao.reserver} 自动裁剪、
     * 开启同名合并时旧源记录被删除、用户清空历史。前两者此前只删历史不删进度，
     * 残留会随使用时间单调增长（每部片每集一行，永不释放）。</p>
     *
     * @return 实际删除的行数
     */
    public static int deleteOrphaned() {
        try {
            return AppDataManager.get().getPlayProgressDao().deleteOrphaned();
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }
}