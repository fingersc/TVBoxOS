package com.github.tvbox.osc.util;

import android.content.res.AssetManager;

import com.github.tvbox.osc.base.App;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.HashMap;

public class EpgUtil {

    /**
     * r216：{@code epg_data.json} 有 104KB，解析（含 Gson 建对象）以前是放在
     * {@code App.onCreate} 的主线程上做的 —— 而它只服务"直播频道的台标/EPG"这个冷路径。
     * 现在改由启动后台线程预热，本类自身也要能承受"后台加载、主线程读取"。
     *
     * <p>两个字段都改成 volatile 且 {@link #init()} 加锁：表先整体建好再置 {@code epgDoc}
     * （就绪标志），读线程看到非 null 时表一定已就绪，不会读到"装了一半"的 map。</p>
     */
    private static volatile JsonObject epgDoc = null;
    private static volatile HashMap<String, JsonObject> epgHashMap = new HashMap<>();

    public static synchronized void init() {
        if(epgDoc != null)
            return;
        try {
            AssetManager assetManager = App.getInstance().getAssets(); //获得assets资源管理器（assets中的文件无法直接访问，可以使用AssetManager访问）
            InputStreamReader inputStreamReader = new InputStreamReader(assetManager.open("epg_data.json"),"UTF-8"); //使用IO流读取json文件内容
            BufferedReader br = new BufferedReader(inputStreamReader);//使用字符高效流
            String line;
            StringBuilder builder = new StringBuilder();
            while ((line = br.readLine())!=null){
                builder.append(line);
            }
            br.close();
            inputStreamReader.close();
            if(!builder.toString().isEmpty()){
                JsonObject doc = new Gson().fromJson(builder.toString(), (Type)JsonObject.class);// 从builder中读取了json中的数据。
                HashMap<String, JsonObject> map = new HashMap<>();
                for (JsonElement opt : doc.get("epgs").getAsJsonArray()) {
                    JsonObject obj = (JsonObject) opt;
                    String name = obj.get("name").getAsString().trim();
                    String[] names  = name.split(",");
                    for (String string : names) {
                        map.put(string,obj);
                    }
                }
                // 先发布表、再发布就绪标志（两个 volatile 写，顺序由 happens-before 保证）
                epgHashMap = map;
                epgDoc = doc;
                return;
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** r216：启动后台线程调用；重复调用安全。 */
    public static void warmUp() {
        try {
            init();
        } catch (Throwable ignored) {
        }
    }

    public static String[] getEpgInfo(String channelName) {
        try {
            // r216：预热还没跑完（例如启动后立刻进直播）时兜底同步加载一次
            if (epgDoc == null) {
                init();
            }
            HashMap<String, JsonObject> map = epgHashMap;
            if(map.containsKey(channelName)){
                JsonObject obj = map.get(channelName);
                return new String[] {
                        obj.get("logo").getAsString(),
                        obj.get("epgid").getAsString()
                };
            }
        }catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }
}
