package com.github.tvbox.osc.ui.adapter;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentTransaction;

import com.github.tvbox.osc.base.BaseLazyFragment;

import java.util.List;

/**
 * @user acer
 * @date 2018/12/4
 *
 * r108：恢复 FragmentPagerAdapter 标准生命周期。
 * 原来 destroyItem() 被改成 hide()（不 detach/remove）、instantiateItem() 额外 show()，
 * 导致离屏页 fragment 永不 onDestroyView、View 与点击监听长期存活；配合
 * HomeActivity.clearHomePages()/refreshHome 重建时，容易残留「已脱离 Activity 仍可点击」
 * 的 fragment 视图 —— 一旦点击即经 jumpActivity→Fragment.startActivity 抛
 * IllegalStateException: Fragment not attached to Activity（本次闪退根因）。
 * 现改为交回 super：离屏页 detach（销毁 View），回屏 attach（复用同一 fragment 实例），
 * 从根上消除可点击的残留 View。
 */
public class HomePageAdapter extends FragmentPagerAdapter {
    public FragmentManager fragmentManager;
    public List<BaseLazyFragment> list;
    private final long adapterId = System.nanoTime();

    public HomePageAdapter(FragmentManager fm) {
        super(fm);
    }

    public HomePageAdapter(FragmentManager fm, List<BaseLazyFragment> list) {
        super(fm);
        this.fragmentManager = fm;
        this.list = list;
    }

    public void clear() {
        if (list == null) return;
        list.clear();
        notifyDataSetChanged();
    }

    /**
     * 彻底移除所有页面 fragment：不依赖 isAdded()（detach 后仍可能 isAdded），
     * 统一 remove 并同步执行，避免遗留孤立/残留 fragment。
     */
    public void removeAll() {
        if (list == null || fragmentManager == null) return;
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        for (BaseLazyFragment fragment : list) {
            if (fragment != null) {
                try {
                    transaction.remove(fragment);
                } catch (Exception ignore) {
                    // fragment 已被移除/未加入 FragmentManager 时忽略
                }
            }
        }
        transaction.commitAllowingStateLoss();
        fragmentManager.executePendingTransactions();
        list.clear();
        notifyDataSetChanged();
    }

    @Override
    public Fragment getItem(int position) {
        return list.get(position);
    }

    @Override
    public long getItemId(int position) {
        return adapterId + position;
    }

    @Override
    public int getCount() {
        return list != null ? list.size() : 0;
    }
}
