package com.teamopensmartglasses.sgmlib;

public interface ButtonCallback extends SubscriptionCallback {
    void call(int buttonId, long timestamp, boolean isDown);
}
