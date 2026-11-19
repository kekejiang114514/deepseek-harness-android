package com.dsh.android.kernel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Random;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebSocket 客户端（RFC 6455，仅客户端，仅 TLS）。
 *
 * <p>为什么自己写：官方机器人只能通过长连接收事件（手机没有公网回调地址），
 * 而本项目不引入第三方依赖。这里只实现用得到的部分：握手、文本帧、
 * 分片重组、ping/pong、关闭帧。
 */
public final class QqSocket {

    /** 回调。 */
    public interface Listener {
        void onOpen();
        void onText(String text);
        void onClosed(String reason);
    }

    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean open = false;
    private Thread reader;
    /** 最后一次出错原因，供服务把状态写出来——不然用户只看到「连不上」。 */
    private volatile String lastError = "";

    public String lastError() { return lastError; }

    public boolean isOpen() {
        return open;
    }

    /** 建立连接并启动读线程。 */
    public void connect(String wssUrl, Listener listener) throws Exception {
        this.listener = listener;
        URI uri = URI.create(wssUrl);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        String path = uri.getRawPath() == null || uri.getRawPath().length() == 0 ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault()
                .createSocket();
        s.connect(new InetSocketAddress(host, port), 15000);
        s.startHandshake();

        String key = nonce();
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: DShAndroid\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(req.getBytes("UTF-8"));
        o.flush();

        InputStream is = s.getInputStream();
        String header = readHeaders(is);
        if (header == null || !header.toLowerCase().contains("101")) {
            throw new Exception("WebSocket 握手失败：" + firstLine(header));
        }

        this.socket = s;
        this.in = is;
        this.out = o;
        this.open = true;
        if (listener != null) listener.onOpen();

        reader = new Thread(new Runnable() {
            public void run() { readLoop(); }
        }, "qq-ws-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** 发送文本帧。 */
    public synchronized void send(String text) throws Exception {
        if (!open || out == null) throw new Exception("连接已关闭");
        byte[] payload = text.getBytes("UTF-8");
        writeFrame(0x1, payload);
    }

    public synchronized void close() {
        open = false;
        try {
            if (out != null) writeFrame(0x8, new byte[0]);
        } catch (Throwable ignored) {}
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
    }

    // ---------------------------------------------------------------- 内部

    private Listener listener;

    private void readLoop() {
        ByteArrayOutputStream frag = new ByteArrayOutputStream();
        try {
            while (open) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;

                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
                }

                byte[] mask = null;
                if ((b1 & 0x80) != 0) {          // 服务端一般不加掩码，稳妥起见都处理
                    mask = new byte[4];
                    readFully(in, mask);
                }
                byte[] data = new byte[(int) len];
                readFully(in, data);
                if (mask != null) {
                    for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                }

                if (opcode == 0x9) {             // ping → pong
                    sendPong(data);
                    continue;
                }
                if (opcode == 0xA) continue;     // pong
                if (opcode == 0x8) break;        // close
                if (opcode == 0x1 || opcode == 0x0) {
                    frag.write(data);
                    if (fin) {
                        String text = new String(frag.toByteArray(), "UTF-8");
                        frag.reset();
                        if (listener != null) listener.onText(text);
                    }
                }
            }
        } catch (Throwable e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            boolean was = open;
            open = false;
            if (was && listener != null) {
                listener.onClosed(lastError.length() > 0 ? "断开：" + lastError : "连接已断开");
            }
        }
    }

    private synchronized void sendPong(byte[] payload) {
        try {
            writeFrame(0xA, payload);
        } catch (Throwable ignored) {}
    }

    private void writeFrame(int opcode, byte[] payload) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(0x80 | opcode);
        int len = payload.length;
        if (len < 126) {
            bo.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            bo.write(0x80 | 126);
            bo.write((len >> 8) & 0xFF);
            bo.write(len & 0xFF);
        } else {
            bo.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) bo.write((int) ((long) len >> (8 * i)) & 0xFF);
        }
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        bo.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i % 4]);
        bo.write(masked);
        out.write(bo.toByteArray());
        out.flush();
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new Exception("连接中断");
            off += n;
        }
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) break;
            bo.write(b);
            if (prev == '\r' && b == '\n') {
                // 连续两个 CRLF 结束
                byte[] all = bo.toByteArray();
                int n = all.length;
                if (n >= 4 && all[n - 4] == '\r' && all[n - 3] == '\n'
                        && all[n - 2] == '\r' && all[n - 1] == '\n') break;
            }
            prev = b;
            if (bo.size() > 16384) throw new Exception("响应头过长");
        }
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String firstLine(String header) {
        if (header == null) return "无响应";
        int i = header.indexOf("\r\n");
        return i > 0 ? header.substring(0, i) : header;
    }

    private static String nonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    void setListener(Listener l) { this.listener = l; }
}
