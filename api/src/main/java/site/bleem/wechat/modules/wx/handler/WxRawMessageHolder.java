package site.bleem.wechat.modules.wx.handler;

/**
 * 当前请求的微信原始 XML，供转发规则原样转发。仅明文模式下可用，加密模式下为 null。
 */
public final class WxRawMessageHolder {
    private static final ThreadLocal<String> RAW_XML = new ThreadLocal<>();

    private WxRawMessageHolder() {
    }

    public static void set(String rawXml) {
        RAW_XML.set(rawXml);
    }

    public static String get() {
        return RAW_XML.get();
    }

    public static void clear() {
        RAW_XML.remove();
    }
}
