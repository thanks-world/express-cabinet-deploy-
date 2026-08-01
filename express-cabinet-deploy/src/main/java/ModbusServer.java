import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import javax.crypto.Cipher;
import java.util.concurrent.atomic.AtomicBoolean;

public class ModbusServer {
    public final boolean[] coils = new boolean[32];
    public final boolean[] discreteInputs = new boolean[32];
    public final int[] holdingRegisters = new int[32];
    public final int[] inputRegisters = new int[32];
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    private PrivateKey rsaPrivateKey;
    private PublicKey rsaPublicKey;
    private static final String DEFAULT_PASSWORD = "123456";
    private static final long PERMANENT_EXPIRE = -1L;

    private final String[] drawerPasswordHash = new String[10];
    private final String[] drawerPasswordSalt = new String[10];
    private final long[] drawerPasswordExpire = new long[10];

    public PublicKey getPublicKey() {
        return rsaPublicKey;
    }

    public void start(int port) throws IOException {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair pair = keyGen.generateKeyPair();
            rsaPrivateKey = pair.getPrivate();
            rsaPublicKey = pair.getPublic();
            String publicKeyStr = Base64.getEncoder().encodeToString(rsaPublicKey.getEncoded());
            System.out.println("[加密] RSA密钥对生成完成，服务端公钥：");
            System.out.println(publicKeyStr);
            System.out.println("公钥Base64长度: " + publicKeyStr.length());
        } catch (Exception e) {
            throw new IOException("RSA密钥生成失败: " + e.getMessage());
        }

        for (int i = 0; i < 10; i++) {
            resetToDefaultPassword(i);
        }
        System.out.println("[密码] 所有抽屉已初始化默认密码：123456");

        startExpireChecker();

        serverSocket = new ServerSocket(port);
        running.set(true);
        new Thread(() -> {
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                } catch (IOException ignored) {}
            }
        }).start();
    }

    private void startExpireChecker() {
        new Thread(() -> {
            while (running.get()) {
                long now = System.currentTimeMillis();
                for (int i = 0; i < 10; i++) {
                    if (drawerPasswordExpire[i] > 0 && now > drawerPasswordExpire[i]) {
                        resetToDefaultPassword(i);
                        System.out.println("[密码] 抽屉" + (i+1) + " 自定义密码已到期，已恢复默认密码");
                    }
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    private void resetToDefaultPassword(int index) {
        try {
            String salt = generateSalt();
            String hash = hashPassword(DEFAULT_PASSWORD, salt);
            drawerPasswordHash[index] = hash;
            drawerPasswordSalt[index] = salt;
            drawerPasswordExpire[index] = PERMANENT_EXPIRE;
        } catch (Exception ignored) {}
    }

    public void stop() {
        running.set(false);
        try { serverSocket.close(); } catch (IOException ignored) {}
    }

    private byte[] rsaDecrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey);
        return cipher.doFinal(data);
    }

    private String hashPassword(String password, String salt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest((password + salt).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public boolean isDrawerLocked(int index) {
        return drawerPasswordHash[index] != null;
    }

    public boolean verifyDrawerPassword(int index, String encryptedPasswordBase64) {
        try {
            if (!isDrawerLocked(index)) return true;
            byte[] encrypted = Base64.getDecoder().decode(encryptedPasswordBase64);
            if (encrypted.length != 256) {
                System.err.println("[错误] 密文长度异常，解密必然失败");
                return false;
            }
            String password = new String(rsaDecrypt(encrypted), StandardCharsets.UTF_8);
            String hash = hashPassword(password, drawerPasswordSalt[index]);
            boolean result = hash.equals(drawerPasswordHash[index]);
            System.out.println("[调试-网络验证] 解密后明文: [" + password + "] 验证结果: " + result);
            return result;
        } catch (Exception e) {
            System.err.println("[密码] 网络端验证失败: " + e.getMessage());
            return false;
        }
    }

    public boolean verifyDrawerPasswordPlain(int index, String plainPassword) {
        try {
            if (!isDrawerLocked(index)) return true;
            String hash = hashPassword(plainPassword, drawerPasswordSalt[index]);
            return hash.equals(drawerPasswordHash[index]);
        } catch (Exception e) {
            System.err.println("[密码] 本地端验证失败: " + e.getMessage());
            return false;
        }
    }

    public boolean setDrawerPassword(int index, String encryptedPasswordBase64, int days) {
        if (days < 1 || days > 7) return false;
        try {
            byte[] encrypted = Base64.getDecoder().decode(encryptedPasswordBase64);
            String password = new String(rsaDecrypt(encrypted), StandardCharsets.UTF_8);
            String salt = generateSalt();
            String hash = hashPassword(password, salt);
            drawerPasswordHash[index] = hash;
            drawerPasswordSalt[index] = salt;
            drawerPasswordExpire[index] = System.currentTimeMillis() + days * 86400000L;
            System.out.println("[密码] 抽屉" + (index+1) + " 自定义密码设置成功，有效期" + days + "天");
            return true;
        } catch (Exception e) {
            System.err.println("[密码] 设置失败: " + e.getMessage());
            return false;
        }
    }

    public boolean setDrawerPasswordPlain(int index, String plainPassword, int days) {
        if (days < 1 || days > 7) return false;
        try {
            String salt = generateSalt();
            String hash = hashPassword(plainPassword, salt);
            drawerPasswordHash[index] = hash;
            drawerPasswordSalt[index] = salt;
            drawerPasswordExpire[index] = System.currentTimeMillis() + days * 86400000L;
            System.out.println("[密码] 抽屉" + (index + 1) + " 自定义密码设置成功(明文)，有效期" + days + "天");
            return true;
        } catch (Exception e) {
            System.err.println("[密码] 明文设置失败: " + e.getMessage());
            return false;
        }
    }

    public void clearDrawerPassword(int index) {
        drawerPasswordHash[index] = null;
        drawerPasswordSalt[index] = null;
        drawerPasswordExpire[index] = 0;
    }

    public int getRemainingHours(int index) {
        if (!isDrawerLocked(index)) return 0;
        if (drawerPasswordExpire[index] == PERMANENT_EXPIRE) return -1;
        long remain = drawerPasswordExpire[index] - System.currentTimeMillis();
        return (int) Math.max(0, remain / 3600000);
    }

    // ---------- Modbus请求处理 ----------
    private void handleClient(Socket client) {
        try (InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {
            byte[] request = new byte[1024];
            int len = in.read(request);
            if (len > 8) {
                int func = request[7] & 0xFF;
                byte[] response = null;
                switch (func) {
                    case 0x64: response = handleSetPassword(request); break;
                    case 0x65: response = handleVerifyPassword(request); break;
                    case 0x66: response = handleQueryLockStatus(request); break;
                    case 0x67: response = handleGetPublicKey(request); break;
                    case 0x68: response = handleSetTempWithPassword(request); break;
                    case 1: response = handleReadCoils(request); break;
                    case 2: response = handleReadDiscreteInputs(request); break;
                    case 3: response = handleReadHoldingRegisters(request); break;
                    case 4: response = handleReadInputRegisters(request); break;
                    case 5: response = handleWriteSingleCoil(request); break;
                    case 6: response = handleWriteSingleRegister(request); break;
                    default: response = errorResponse(request, 1); break;
                }
                if (response != null) out.write(response);
            }
        } catch (IOException e) { }
    }

    // 0x67 获取RSA公钥（格式：单元标识符+功能码+Base64数据）
    private byte[] handleGetPublicKey(byte[] req) {
        byte[] keyBytes = rsaPublicKey.getEncoded();
        String keyStr = Base64.getEncoder().encodeToString(keyBytes);
        byte[] keyData = keyStr.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[2 + keyData.length];
        data[0] = req[6];      // 单元标识符
        data[1] = req[7];      // 功能码 0x67
        System.arraycopy(keyData, 0, data, 2, keyData.length);
        byte[] header = buildResponseHeader(req, data.length);
        byte[] resp = new byte[header.length + data.length];
        System.arraycopy(header, 0, resp, 0, header.length);
        System.arraycopy(data, 0, resp, header.length, data.length);
        return resp;
    }

    // 0x64 设置抽屉密码
    private byte[] handleSetPassword(byte[] req) {
        int drawerId = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        int days = ((req[10] & 0xFF) << 8) | (req[11] & 0xFF);
        int totalAfterLen = ((req[4] & 0xFF) << 8) | (req[5] & 0xFF);
        int pwdLen = totalAfterLen - 6;
        byte[] pwdBytes = new byte[pwdLen];
        System.arraycopy(req, 12, pwdBytes, 0, pwdLen);
        String encryptedPwd = new String(pwdBytes, StandardCharsets.UTF_8);
        boolean success = setDrawerPassword(drawerId - 1, encryptedPwd, days);
        byte[] resp = new byte[12];
        System.arraycopy(req, 0, resp, 0, 6);
        resp[4] = 0x00; resp[5] = 0x06;
        resp[6] = req[6];
        resp[7] = req[7];
        resp[8] = (byte)(drawerId >> 8);
        resp[9] = (byte)(drawerId & 0xFF);
        resp[10] = success ? (byte)0xFF : 0x00;
        resp[11] = 0x00;
        return resp;
    }

    // 0x65 验证抽屉密码
    private byte[] handleVerifyPassword(byte[] req) {
        int drawerId = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        int totalAfterLen = ((req[4] & 0xFF) << 8) | (req[5] & 0xFF);
        int pwdLen = totalAfterLen - 4;
        byte[] pwdBytes = new byte[pwdLen];
        System.arraycopy(req, 10, pwdBytes, 0, pwdLen);
        String encryptedPwd = new String(pwdBytes, StandardCharsets.UTF_8);
        boolean pass = verifyDrawerPassword(drawerId - 1, encryptedPwd);
        byte[] resp = new byte[12];
        System.arraycopy(req, 0, resp, 0, 6);
        resp[4] = 0x00; resp[5] = 0x06;
        resp[6] = req[6];
        resp[7] = req[7];
        resp[8] = (byte)(drawerId >> 8);
        resp[9] = (byte)(drawerId & 0xFF);
        resp[10] = pass ? (byte)0xFF : 0x00;
        resp[11] = 0x00;
        return resp;
    }

    // 0x66 查询锁定状态与剩余时间
    private byte[] handleQueryLockStatus(byte[] req) {
        int drawerId = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        boolean locked = isDrawerLocked(drawerId - 1);
        int remainHours = getRemainingHours(drawerId - 1);
        byte[] data = new byte[5];
        data[0] = req[6];
        data[1] = req[7];
        data[2] = (byte) (locked ? 0xFF : 0x00);
        data[3] = (byte)(remainHours >> 8);
        data[4] = (byte)(remainHours & 0xFF);
        byte[] header = buildResponseHeader(req, data.length);
        byte[] resp = new byte[header.length + data.length];
        System.arraycopy(header, 0, resp, 0, header.length);
        System.arraycopy(data, 0, resp, header.length, data.length);
        return resp;
    }

    // 0x68 带密码验证设置抽屉温度
    private byte[] handleSetTempWithPassword(byte[] req) {
        int drawerId = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        int tempVal = ((req[10] & 0xFF) << 8) | (req[11] & 0xFF);
        int index = drawerId - 1;

        if (isDrawerLocked(index)) {
            int totalAfterLen = ((req[4] & 0xFF) << 8) | (req[5] & 0xFF);
            int pwdLen = totalAfterLen - 6;
            byte[] pwdBytes = new byte[pwdLen];
            System.arraycopy(req, 12, pwdBytes, 0, pwdLen);
            String encryptedPwd = new String(pwdBytes, StandardCharsets.UTF_8);
            if (!verifyDrawerPassword(index, encryptedPwd)) {
                byte[] resp = new byte[12];
                System.arraycopy(req, 0, resp, 0, 6);
                resp[4] = 0x00; resp[5] = 0x06;
                resp[6] = req[6];
                resp[7] = req[7];
                resp[8] = (byte)(drawerId >> 8);
                resp[9] = (byte)(drawerId & 0xFF);
                resp[10] = 0x00;
                resp[11] = 0x00;
                return resp;
            }
        }
        if (index >= 0 && index < holdingRegisters.length - 1) {
            holdingRegisters[drawerId] = tempVal;
            System.out.println("[温度] 抽屉" + drawerId + " 设定温度修改为: " + (tempVal/10.0) + "℃");
        }
        byte[] resp = new byte[12];
        System.arraycopy(req, 0, resp, 0, 6);
        resp[4] = 0x00; resp[5] = 0x06;
        resp[6] = req[6];
        resp[7] = req[7];
        resp[8] = (byte)(drawerId >> 8);
        resp[9] = (byte)(drawerId & 0xFF);
        resp[10] = (byte)0xFF;
        resp[11] = 0x00;
        return resp;
    }

    // 标准Modbus功能码
    private byte[] handleReadCoils(byte[] req) {
        int start = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        int count = ((req[10] & 0xFF) << 8) | (req[11] & 0xFF);
        int byteCount = (count + 7) / 8;
        byte[] data = new byte[3 + byteCount];
        data[0] = req[6];
        data[1] = 0x01;
        data[2] = (byte) byteCount;
        int bits = 0, pos = 3;
        for (int i = 0; i < count; i++) {
            if (coils[start + i]) bits |= (1 << (i % 8));
            if ((i + 1) % 8 == 0 || i == count - 1) {
                data[pos++] = (byte) bits;
                bits = 0;
            }
        }
        byte[] header = buildResponseHeader(req, 3 + byteCount);
        byte[] resp = new byte[6 + data.length];
        System.arraycopy(header, 0, resp, 0, 6);
        System.arraycopy(data, 0, resp, 6, data.length);
        return resp;
    }

    private byte[] handleReadDiscreteInputs(byte[] req) {
        int start = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        int count = ((req[10] & 0xFF) << 8) | (req[11] & 0xFF);
        int byteCount = (count + 7) / 8;
        byte[] data = new byte[3 + byteCount];
        data[0] = req[6];
        data[1] = 0x02;
        data[2] = (byte) byteCount;
        int bits = 0, pos = 3;
        for (int i = 0; i < count; i++) {
            if (discreteInputs[start + i]) bits |= (1 << (i % 8));
            if ((i + 1) % 8 == 0 || i == count - 1) {
                data[pos++] = (byte) bits;
                bits = 0;
            }
        }
        byte[] header = buildResponseHeader(req, 3 + byteCount);
        byte[] resp = new byte[6 + data.length];
        System.arraycopy(header, 0, resp, 0, 6);
        System.arraycopy(data, 0, resp, 6, data.length);
        return resp;
    }

    private byte[] handleReadHoldingRegisters(byte[] req) {
        int start = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        int count = ((req[10] & 0xFF) << 8) | (req[11] & 0xFF);
        byte[] data = new byte[3 + 2 * count];
        data[0] = req[6];
        data[1] = 0x03;
        data[2] = (byte)(2 * count);
        int pos = 3;
        for (int i = 0; i < count; i++) {
            int val = holdingRegisters[start + i];
            data[pos++] = (byte)(val >> 8);
            data[pos++] = (byte)(val & 0xFF);
        }
        byte[] header = buildResponseHeader(req, 3 + 2 * count);
        byte[] resp = new byte[6 + data.length];
        System.arraycopy(header, 0, resp, 0, 6);
        System.arraycopy(data, 0, resp, 6, data.length);
        return resp;
    }

    private byte[] handleReadInputRegisters(byte[] req) {
        int start = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        int count = ((req[10] & 0xFF) << 8) | (req[11] & 0xFF);
        byte[] data = new byte[3 + 2 * count];
        data[0] = req[6];
        data[1] = 0x04;
        data[2] = (byte)(2 * count);
        int pos = 3;
        for (int i = 0; i < count; i++) {
            int val = inputRegisters[start + i];
            data[pos++] = (byte)(val >> 8);
            data[pos++] = (byte)(val & 0xFF);
        }
        byte[] header = buildResponseHeader(req, 3 + 2 * count);
        byte[] resp = new byte[6 + data.length];
        System.arraycopy(header, 0, resp, 0, 6);
        System.arraycopy(data, 0, resp, 6, data.length);
        return resp;
    }

    private byte[] handleWriteSingleCoil(byte[] req) {
        int addr = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        boolean val = (req[10] & 0xFF) == 0xFF;
        coils[addr] = val;
        return req;
    }

    private byte[] handleWriteSingleRegister(byte[] req) {
        int addr = ((req[8] & 0xFF) << 8) | (req[9] & 0xFF);
        int val = ((req[10] & 0xFF) << 8) | (req[11] & 0xFF);
        holdingRegisters[addr] = val;
        return req;
    }

    private byte[] errorResponse(byte[] req, int code) {
        byte[] data = new byte[3];
        data[0] = req[6];
        data[1] = (byte)(req[7] | 0x80);
        data[2] = (byte) code;
        byte[] header = buildResponseHeader(req, 3);
        byte[] resp = new byte[6 + 3];
        System.arraycopy(header, 0, resp, 0, 6);
        System.arraycopy(data, 0, resp, 6, 3);
        return resp;
    }

    private byte[] buildResponseHeader(byte[] request, int dataLen) {
        byte[] header = new byte[6];
        System.arraycopy(request, 0, header, 0, 4);
        header[4] = (byte)(dataLen >> 8);
        header[5] = (byte)(dataLen & 0xFF);
        return header;
    }
}