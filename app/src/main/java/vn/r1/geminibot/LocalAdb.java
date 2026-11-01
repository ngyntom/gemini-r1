package vn.r1.geminibot;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Client adb tối giản, nối vào adbd của chính loa (127.0.0.1:5555) để chạy lệnh shell
 * với quyền "shell" (vd. pm install). Chỉ chạy khi adbd không đòi xác thực
 * (ro.adb.secure=0, như trên Phicomm R1).
 */
final class LocalAdb {
    private static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257, AUTH = 0x48545541;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;

    private LocalAdb() {}

    /**
     * Chạy lệnh shell, trả về toàn bộ output (đợi đến khi lệnh kết thúc).
     * adbd của R1 chỉ phục vụ một kết nối mỗi lúc, nên các lệnh chạy lần lượt.
     */
    static synchronized String shell(String cmd, int timeoutMs) throws IOException {
        Socket s = new Socket("127.0.0.1", 5555);
        try {
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());

            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes("UTF-8"));
            Msg m = read(in);
            if (m.cmd == AUTH) throw new IOException("adbd đòi xác thực (ro.adb.secure=1)");
            if (m.cmd != CNXN) throw new IOException("adb: trả lời lạ " + Integer.toHexString(m.cmd));

            int local = 1;
            send(out, OPEN, local, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                m = read(in);
                if (m.cmd == OKAY) {
                    remote = m.arg0;
                } else if (m.cmd == WRTE) {
                    result.write(m.data);
                    send(out, OKAY, local, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE) {
                    if (remote != 0) send(out, CLSE, local, remote, new byte[0]);
                    break;
                }
            }
            return result.toString("UTF-8");
        } finally {
            s.close();
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }
}
