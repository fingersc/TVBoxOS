package com.github.tvbox.osc.util;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class HawkConfig {
    public static final String API_URL = "api_url";
    public static final String EPG_URL = "epg_url";
    public static final String SHOW_PREVIEW = "show_preview";
    public static final String API_HISTORY = "api_history";
    public static final String API_LINE_LIST = "api_line_list";
    public static final String API_LINE_SOURCE = "api_line_source";
    public static final String LIVE_API_HISTORY = "live_api_history";
    public static final String EPG_HISTORY = "epg_history";
    public static final String HOME_API = "home_api";
    public static final String DEFAULT_PARSE = "parse_default";
    public static final String DEBUG_OPEN = "debug_open";
    public static final String PARSE_WEBVIEW = "parse_webview"; // true 系统 false xwalk
    public static final String IJK_CODEC = "ijk_codec";
    public static final String PLAY_TYPE = "play_type";//1 ijk 2 exo 10 MXPlayer
    public static final String LIVE_PLAY_TYPE = "live_play_type";//1 ijk 2 exo 10 MXPlayer
    /** v57 一次性开关：已把直播默认播放引擎复位成 EXO，见 LivePlayerManager#resetDefaultOnce */
    public static final String LIVE_PLAY_TYPE_RESET_EXO_V57 = "live_play_type_reset_exo_v57";
    public static final String PLAY_RENDER = "play_render"; //0 texture 2
    public static final String PLAY_SCALE = "play_scale"; //0 texture 2
    public static final String LIVE_PLAY_SCALE = "live_play_scale";
    public static final String PLAY_TIME_STEP = "play_time_step"; //0 texture 2
    public static final String DOH_URL = "doh_url";
    public static final int DEFAULT_HOME_REC = 1;
    public static final String HOME_REC = "home_rec"; // 0 豆瓣热播 1 数据源推荐 2 历史
    public static final String HISTORY_NUM = "history_num";
    public static final String HISTORY_MERGE = "history_merge";
    public static final String SEARCH_VIEW = "search_view"; // 0 列表 1 缩略图
    public static final String LIVE_CHANNEL = "last_live_channel_name";
    public static final String LIVE_CHANNEL_REVERSE = "live_channel_reverse";
    public static final String LIVE_CROSS_GROUP = "live_cross_group";
    public static final String LIVE_CONNECT_TIMEOUT = "live_connect_timeout";
    public static final String LIVE_SHOW_NET_SPEED = "live_show_net_speed";
    public static final String LIVE_SHOW_RESOLUTION = "live_show_resolution";
    public static final String LIVE_SHOW_TIME = "live_show_time";
    public static final String FAST_SEARCH_MODE = "fast_search_mode";
    public static final String SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    public static final String SUBTITLE_TIME_DELAY = "subtitle_time_delay";
    public static final String SUBTITLE_EXO_SCALE = "subtitle_exo_scale";
    public static final String SUBTITLE_EXO_POSITION = "subtitle_exo_position";
    public static final String SOURCES_FOR_SEARCH = "checked_sources_for_search";
    public static final String HOME_REC_STYLE = "home_rec_style";
    public static final String NOW_DATE = "now_date"; //当前日期
    public static final String REMOTE_TVBOX = "remote_tvbox_host";
    public static final String IJK_CACHE_PLAY = "ijk_cache_play";
    public static final String PLAYER_IS_LIVE = "player_is_live";
    public static final String DOH_JSON = "doh_json";
    public static final String LIVE_GROUP_INDEX = "live_group_index";
    public static final String LIVE_GROUP_LIST = "live_group_list";
    public static final String LIVE_API_URL = "live_api_url";
    public static final String M3U8_PURIFY = "m3u8_purify";
    public static final String AUTO_SWITCH_LINE = "auto_switch_line";
    public static final String SCREEN_DISPLAY = "screen_display";
    public static final String LIVE_WEB_HEADER = "live_web_header";
    public static final String DEFAULT_LOAD_LIVE = "DEFAULT_LOAD_LIVE";
    public static final String SEARCH_HISTORY = "search_history";
    /**
     * 最近一次执行的搜索词。
     *
     * <p>用于进程被系统回收后恢复搜索页：Activity 重建时 {@code Intent} 会被重放，
     * 但搜索结果只存在内存里，重建后必须重新搜；如果重建发生在"从搜索页跳详情页"
     * 期间（此时搜索已暂停、结果不回填），用户回到搜索页就会看到一个空白页。</p>
     */
    public static final String LAST_SEARCH_KEYWORD = "last_search_keyword";
    public static final String DANMU_OPEN = "danmu_open";
    public static final String DANMU_MAX_LINE = "danmu_max_line";
    public static final String DANMU_SPEED = "danmu_speed";
    public static final String DANMU_ALPHA = "danmu_alpha";
    public static final String DANMU_SIZE_SCALE = "danmu_size_scale";
    public static final String DANMU_RANDOM_COLOR = "danmu_random_color";
    public static final String DANMU_API = "danmu_api";

    // ── v73 账号与同步 ──────────────────────────────────────────────
    /** WebDAV 服务器地址（含根目录） */
    public static final String SYNC_WEBDAV_URL = "sync_webdav_url";
    public static final String SYNC_WEBDAV_USER = "sync_webdav_user";
    public static final String SYNC_WEBDAV_PASS = "sync_webdav_pass";
    /** 当前登录账号（JSON：owner/name/keyHex） */
    public static final String SYNC_ACCOUNT = "sync_account";
    /** 账号方案版本：2 = 文件夹即账号（v74）；旧版本启动时自动登出 */
    public static final String SYNC_SCHEME = "sync_scheme";
    /** 设备唯一标识（首次生成） */
    public static final String SYNC_DEVICE_ID = "sync_device_id";
    /** 上次同步时间 / 云端 epoch / 待同步标记 */
    public static final String SYNC_LAST_AT = "sync_last_at";
    public static final String SYNC_LAST_EPOCH = "sync_last_epoch";
    public static final String SYNC_DIRTY = "sync_dirty";
    /** 仅 Wi-Fi 同步 */
    public static final String SYNC_WIFI_ONLY = "sync_wifi_only";
    /** 同步范围（JSON：history/progress/collect/settings） */
    public static final String SYNC_SCOPES = "sync_scopes";
    /** 上次同步时的设置快照（JSON，按账号：sync_settings_snapshot.<owner>） */
    public static final String SYNC_SETTINGS_SNAPSHOT = "sync_settings_snapshot";
    /** v97：直播起播是否启用 IPv4/IPv6 并行优选（默认开；出问题可关，退回 v94 行为） */
    public static final String LIVE_DUALSTACK = "live_dualstack";
    /** 该账号的 profile.json 已确认存在的缓存（v78：省掉每次同步的一次 GET） */
    public static final String PROFILE_OK_PREFIX = "profile_ok.";
    /** 本机账号清单（JSON 数组：name/owner/addedAt/lastUsedAt），v76 */
    public static final String ACCOUNT_LIST = "account_list";
    /** 账号级设置快照（JSON，按账号：settings_profile.<owner>），v76 */
    public static final String SYNC_SETTINGS_PROFILE = "settings_profile";
    /** 该账号尚未从云端拉过设置（首次加入）：首次同步只拉不推，v76 */
    public static final String SYNC_SETTINGS_PENDING = "sync_settings_pending";
    /** 待上传的删除墓碑（JSON，按账号：sync_tombs.<owner>） */
    public static final String SYNC_TOMBS = "sync_tombs";
    /** 连续失败次数 / 退避截止时间（按账号） */
    public static final String SYNC_FAIL_COUNT = "sync_fail_count";
    public static final String SYNC_FAIL_UNTIL = "sync_fail_until";
    public static boolean hotVodDelete;
}
