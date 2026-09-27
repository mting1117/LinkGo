package com.moting.linkgo.clipboard.dex;

public record Event(EventEnum event, String content) {
    @Override
    public String toString() {
        return event.name() + ":" + content;
    }
}