package com.moting.linkgo.clipboard.dex;

import android.content.IClipboard;

import java.time.LocalDateTime;

public class PrimaryClipChangedListenerAdapter extends OnPrimaryClipChangedListenerAdapter {
    public PrimaryClipChangedListenerAdapter(IClipboard clipboard) throws NoSuchMethodException {
        super(clipboard);
    }

    public PrimaryClipChangedListenerAdapter(IClipboard clipboard, String pkg, String tag, int userId, int devId) throws NoSuchMethodException {
        super(clipboard, pkg, tag, userId, devId);
    }

    @Override
    public void onPrimaryClipChanged() {
        System.out.println(new Event(EventEnum.onChanged, LocalDateTime.now().toString()));
    }
}