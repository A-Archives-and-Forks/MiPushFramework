package com.xiaomi.push.service;

import android.content.Context;
import android.content.Intent;

import com.elvishew.xlog.Logger;
import com.elvishew.xlog.XLog;
import com.xiaomi.slim.Blob;
import com.xiaomi.smack.packet.CommonPacketExtension;
import com.xiaomi.smack.packet.Message;
import com.xiaomi.smack.packet.Packet;
import com.xiaomi.smack.util.TrafficUtils;
import com.xiaomi.xmpush.thrift.ActionType;
import com.xiaomi.xmpush.thrift.PushMetaInfo;
import com.xiaomi.xmpush.thrift.XmPushActionContainer;
import com.xiaomi.xmsf.push.type.TypeFactory;

import java.lang.reflect.Field;

import top.trumeet.common.BuildConfig;
import top.trumeet.common.db.EventDb;
import top.trumeet.common.db.RegisteredApplicationDb;
import top.trumeet.common.event.Event;
import top.trumeet.common.event.type.EventType;
import top.trumeet.common.register.RegisteredApplication;

/**
 * Created by Trumeet on 2018/1/22.
 * 修改过的 ClientEventDispatcher，用于修改包接收处理逻辑
 * <p>
 * 消息的处理：
 * 发送方（framework）：
 * <p>
 * 广播 1： {@link PushConstants#MIPUSH_ACTION_MESSAGE_ARRIVED}
 * {@link MIPushEventProcessor} 负责将序列化后的消息广播/发送通知。
 * 具体可以看到 {@link MIPushEventProcessor#postProcessMIPushMessage(XMPushService, String, byte[], Intent, boolean)}
 * 里面的 170 行。它发送了 {@link PushConstants#MIPUSH_ACTION_MESSAGE_ARRIVED} 广播给客户端。
 * <p>
 * 广播 2： {@link PushConstants#MIPUSH_ACTION_NEW_MESSAGE}；
 * 同样由 {@link MIPushEventProcessor} 发送。最初是在 {@link MIPushEventProcessor#buildIntent(byte[], long)} 中生成，由
 * {@link MIPushEventProcessor#postProcessMIPushMessage(XMPushService, String, byte[], Intent, boolean)} 中 192 行发送。
 * <p>
 * 广播 3： {@link PushConstants#MIPUSH_ACTION_ERROR}
 * 由 {@link MIPushClientManager#notifyError} 发送。
 * <p>
 * 客户端（接收方）：
 * 消息 intent 统一由 {@link com.xiaomi.mipush.sdk.PushMessageProcessor#processIntent} 处理。
 * <p>
 * Warning:
 * 理论上这里是服务器发送给 Framework，然后再由 Framework 发给对方 app 的中转。所以一些请求类的 request（如 {@link ActionType#Subscription}
 * 这里拦截没有任何作用，所以没有在这里处理，仅记录。
 */

public class MyClientEventDispatcher extends ClientEventDispatcher {
    private Logger logger = XLog.tag("MyClientEventDispatcher").build();

    MyClientEventDispatcher() {
        try {
            // Patch mPushEventProcessor
            Field mPushEventProcessorField = ClientEventDispatcher.class
                    .getDeclaredField("mPushEventProcessor");
            mPushEventProcessorField.setAccessible(true);
            Object original = mPushEventProcessorField.get(this);
            if (original == null) {
                logger.e("original is null, patch may not work.");
            }
            logger.d("original: " + original);
            mPushEventProcessorField.set(this, new EventProcessor());
            logger.d("Patch success.");
        } catch (Exception e) {
            logger.e("*** Patch failed, core functions may not work.");
        }
    }

    @Override
    public void notifyPacketArrival(XMPushService xMPushService, String str, Packet packet) {
        if (BuildConfig.DEBUG) {
            logger.d("packet arrival: " + str + "; " + packet.toXML());
        }
        super.notifyPacketArrival(xMPushService, str, packet);
    }

    @Override
    public void notifyPacketArrival(XMPushService xMPushService, String str, Blob blob) {
        if (BuildConfig.DEBUG) {
            logger.d("blob arrival: " + str + "; " + blob.toString());
        }
        super.notifyPacketArrival(xMPushService, str, blob);
    }

    public static int getNotificationId(Context context, XmPushActionContainer paramXmPushActionContainer) {
        final PushMetaInfo metaInfo = paramXmPushActionContainer.getMetaInfo();
        String id = metaInfo.isSetNotifyId() ? String.valueOf(metaInfo.getNotifyId()) : metaInfo.getId();
        String idWithPackage = MIPushNotificationHelper.getTargetPackage(paramXmPushActionContainer) + "_" + id;
        return idWithPackage.hashCode();
    }

    /**
     * 处理收到的消息
     */
    private static class MessageProcessor {
        private static Logger logger = XLog.tag("EventProcessorI").build();
        private static boolean userAllow(EventType type, Context context) {
            RegisteredApplication application = RegisteredApplicationDb.registerApplication(type.getPkg(),
                    false, context, null);
            if (application == null) {
                return false;
            }
            boolean allow = isAllowByConfig(type, application);
            logger.d("insertEvent -> " + type);
            EventDb.insertEvent(allow ? Event.ResultType.OK : Event.ResultType.DENY_USER
                    , type, context);
            return allow;
        }

        private static boolean isAllowByConfig(EventType type, RegisteredApplication application) {
            switch (type.getType()) {
                case Event.Type.Command:
                    return application.isAllowReceiveCommand();
                case Event.Type.Notification:
                    return application.getAllowReceivePush();
                default:
                    logger.e("Unknown type: " + type.getType());
                    return true;
            }
        }
    }

    private static class EventProcessor extends MIPushEventProcessor {
        private static Logger logger = XLog.tag("MyClientEventDispatcherD").build();
        private static void runProcessMIPushMessage(XMPushService pushService, byte[] decryptedContent, long packetBytesLen) {
            XmPushActionContainer buildContainer = buildContainer(decryptedContent);
            if (BuildConfig.DEBUG) {
                logger.i("buildContainer: " + buildContainer.toString());
            }
            EventType type = TypeFactory.create(buildContainer, buildContainer.packageName);
            if (MessageProcessor.userAllow(type, pushService) ||
                    PushConstants.PUSH_SERVICE_PACKAGE_NAME.equals(buildContainer.packageName)) {

                MyMIPushMessageProcessor.processMIPushMessage(pushService, decryptedContent, packetBytesLen);

            } else {
                if (BuildConfig.DEBUG) {
                    logger.d("denied.");
                }
            }
        }

        @Override
        public void processNewPacket(XMPushService pushService, Blob blob, PushClientsManager.ClientLoginInfo loginInfo) {
            try {
                byte[] decryptedContent = blob.getDecryptedPayload(loginInfo.security);
                runProcessMIPushMessage(pushService, decryptedContent, blob.getSerializedSize());
            } catch (IllegalArgumentException e) {
                logger.e(e);
            }
        }

        @Override
        public void processNewPacket(XMPushService pushService, Packet packet, PushClientsManager.ClientLoginInfo loginInfo) {
            if (packet instanceof Message) {
                Message miMessage = (Message) packet;
                CommonPacketExtension extension = miMessage.getExtension("s");
                if (extension != null) {
                    try {
                        byte[] key = RC4Cryption.generateKeyForRC4(loginInfo.security, miMessage.getPacketID());
                        byte[] decryptedContent = RC4Cryption.decrypt(key, extension.getText());
                        runProcessMIPushMessage(pushService, decryptedContent, TrafficUtils.getTrafficFlow(packet.toXML()));
                        return;
                    } catch (IllegalArgumentException e) {
                        logger.e(e);
                        return;
                    }
                }
                return;
            }
            logger.w("not a mipush message");
        }

    }
}
