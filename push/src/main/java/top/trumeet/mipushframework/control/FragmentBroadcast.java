package top.trumeet.mipushframework.control;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Trumeet
 * @date 2017/12/30
 */

public class FragmentBroadcast {
    private Map<String, Fragment> mChildFragments;

    public void registerFragment(@NonNull String tag,
                                 @NonNull Fragment fragment) {
        if (mChildFragments == null) {
            mChildFragments = new HashMap<>(1);
        }
        mChildFragments.put(tag, fragment);
    }

    public void unregisterFragment(@NonNull String tag) {
        if (mChildFragments == null) {
            return;
        }
        mChildFragments.remove(tag);
        if (mChildFragments.isEmpty()) {
            mChildFragments = null;
        }
    }

    public boolean hasFragment(@NonNull String tag) {
        return mChildFragments != null && mChildFragments.containsKey(tag);
    }

    public Fragment getFragment(@NonNull String tag) {
        return mChildFragments.get(tag);
    }

    public void unregisterAll() {
        if (mChildFragments != null) {
            mChildFragments = null;
        }
    }
}
