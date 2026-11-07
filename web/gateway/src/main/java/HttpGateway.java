import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;

public class HttpGateway {
    private static final String MODBUS_HOST = System.getenv().getOrDefault("MODBUS_HOST", "127.0.0.1");
    private static final int MODBUS_PORT = Integer.parseInt(System.getenv().getOrDefault("MODBUS_PORT", "5020"));
    private static final String REDIS_HOST = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
    private static final int REDIS_PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

    private static final int CACHE_EXPIRE_SEC = 2;
    private static final int MAX_HISTORY_COUNT = 200;
    private static final int MAX_ALARM_COUNT = 100;
    private static JedisPool jedisPool;

    private static final String KEY_REALTIME_STATUS = "cabinet:realtime:status";
    private static final String KEY_TEMP_HISTORY = "cabinet:history:temp";
    private static final String KEY_ALARM_THRESHOLD_HIGH = "cabinet:alarm:threshold:high";
    private static final String KEY_ALARM_THRESHOLD_LOW = "cabinet:alarm:threshold:low";
    private static final String KEY_ALARM_RECORDS = "cabinet:alarm:records";
    private static final String KEY_ALARM_ACTIVE = "cabinet:alarm:active";

    private static final double DEFAULT_HIGH_THRESHOLD = 20.0;
    private static final double DEFAULT_LOW_THRESHOLD = 0.0;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws IOException {
        initRedisPool();
        initDefaultThreshold();
        System.out.println("[HTTP网关] 启动完成，Redis地址: " + REDIS_HOST + ":" + REDIS_PORT);
        System.out.println("[HTTP网关] Modbus地址: " + MODBUS_HOST + ":" + MODBUS_PORT);

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/temp", HttpGateway::handleTemp);
        server.createContext("/temp/set", HttpGateway::handleSetTemp);
        server.createContext("/mode/toggle", HttpGateway::handleModeToggle);
        server.createContext("/drawers", HttpGateway::handleDrawers);
        server.createContext("/drawers/", HttpGateway::handleDrawerControl);
        server.createContext("/drawers/temp", HttpGateway::handleDrawersTemp);
        server.createContext("/drawers/temp/set", HttpGateway::handleSetDrawerTemp);
        server.createContext("/drawers/temp/set-secure", HttpGateway::handleSetDrawerTempSecure);
        server.createContext("/drawers/publickey", HttpGateway::handleGetPublicKey);
        server.createContext("/drawers/password/set", HttpGateway::handleSetDrawerPassword);
        server.createContext("/drawers/password/verify", HttpGateway::handleVerifyDrawerPassword);
        server.createContext("/drawers/password/clear", HttpGateway::handleClearDrawerPassword);
        server.createContext("/drawers/lockstatus", HttpGateway::handleLockStatus);
        server.createContext("/temp/history", HttpGateway::handleTempHistory);
        server.createContext("/alarm/list", HttpGateway::handleAlarmList);
        server.createContext("/alarm/threshold", HttpGateway::handleAlarmThreshold);
        server.createContext("/alarm/clear", HttpGateway::handleAlarmClear);
        server.createContext("/health", ex -> sendJson(ex, "{\"status\":\"ok\"}"));
        server.setExecutor(null);
        server.start();
        System.out.println("[HTTP网关] HTTP服务监听端口: 8080");
    }

    // 发送Modbus请求并返回响应
    private static byte[] sendModbusRaw(byte[] req) throws IOException {
        try (Socket sock = new Socket(MODBUS_HOST, MODBUS_PORT);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {
            out.write(req);
            out.flush();
            byte[] buffer = new byte[1024];
            int len = in.read(buffer);
            if (len < 1) throw new IOException("无响应");
            byte[] resp = new byte[len];
            System.arraycopy(buffer, 0, resp, 0, len);
            return resp;
        }
    }

    // 构建标准Modbus请求头
    private static byte[] buildModbusReq(int func, int dataLen) {
        byte[] req = new byte[6 + dataLen];
        req[0] = 0x00; req[1] = 0x01;
        req[2] = 0x00; req[3] = 0x00;
        req[4] = (byte)(dataLen >> 8);
        req[5] = (byte)(dataLen & 0xFF);
        req[6] = 0x01;
        req[7] = (byte) func;
        return req;
    }

    // ========== 密码相关接口 ==========
    private static void handleGetPublicKey(HttpExchange ex) {
        try {
            byte[] req = buildModbusReq(0x67, 0);
            byte[] resp = sendModbusRaw(req);
            int dataLen = ((resp[4] & 0xFF) << 8) | (resp[5] & 0xFF);
            byte[] keyBytes = new byte[dataLen];
            System.arraycopy(resp, 6, keyBytes, 0, dataLen);
            String publicKey = new String(keyBytes);
            sendJson(ex, "{\"publicKey\":\"" + publicKey + "\"}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleSetDrawerPassword(HttpExchange ex) {
        try {
            String body = readBody(ex);
            int id = Integer.parseInt(body.replaceAll(".*\"id\"[^0-9]*([0-9]+).*", "$1"));
            String encryptedPwd = body.replaceAll(".*\"encryptedPassword\"[^:]*:\"([^\"]+)\".*", "$1");
            int days = Integer.parseInt(body.replaceAll(".*\"days\"[^0-9]*([0-9]+).*", "$1"));

            byte[] pwdBytes = encryptedPwd.getBytes();
            int dataLen = 6 + pwdBytes.length;
            byte[] req = buildModbusReq(0x64, dataLen);
            req[8] = (byte)(id >> 8);
            req[9] = (byte)(id & 0xFF);
            req[10] = (byte)(days >> 8);
            req[11] = (byte)(days & 0xFF);
            System.arraycopy(pwdBytes, 0, req, 12, pwdBytes.length);

            byte[] resp = sendModbusRaw(req);
            boolean success = (resp[10] & 0xFF) == 0xFF;
            sendJson(ex, "{\"success\":" + success + "}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleVerifyDrawerPassword(HttpExchange ex) {
        try {
            String body = readBody(ex);
            int id = Integer.parseInt(body.replaceAll(".*\"id\"[^0-9]*([0-9]+).*", "$1"));
            String encryptedPwd = body.replaceAll(".*\"encryptedPassword\"[^:]*:\"([^\"]+)\".*", "$1");

            byte[] pwdBytes = encryptedPwd.getBytes();
            int dataLen = 4 + pwdBytes.length;
            byte[] req = buildModbusReq(0x65, dataLen);
            req[8] = (byte)(id >> 8);
            req[9] = (byte)(id & 0xFF);
            System.arraycopy(pwdBytes, 0, req, 10, pwdBytes.length);

            byte[] resp = sendModbusRaw(req);
            boolean pass = (resp[10] & 0xFF) == 0xFF;
            sendJson(ex, "{\"success\":" + pass + "}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleClearDrawerPassword(HttpExchange ex) {
        try {
            String body = readBody(ex);
            int id = Integer.parseInt(body.replaceAll(".*\"id\"[^0-9]*([0-9]+).*", "$1"));
            byte[] req = buildModbusReq(0x64, 6);
            req[8] = (byte)(id >> 8);
            req[9] = (byte)(id & 0xFF);
            req[10] = 0x00; req[11] = 0x00;
            sendModbusRaw(req);
            sendJson(ex, "{\"success\":true}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleLockStatus(HttpExchange ex) {
        try {
            StringBuilder sb = new StringBuilder("{\"drawers\":[");
            for (int i = 1; i <= 10; i++) {
                byte[] req = buildModbusReq(0x66, 4);
                req[8] = (byte)(i >> 8);
                req[9] = (byte)(i & 0xFF);
                byte[] resp = sendModbusRaw(req);
                boolean locked = (resp[8] & 0xFF) == 0xFF;
                int remainHours = ((resp[9] & 0xFF) << 8) | (resp[10] & 0xFF);
                sb.append(String.format("{\"id\":%d,\"locked\":%b,\"remainHours\":%d}", i, locked, remainHours));
                if (i < 10) sb.append(",");
            }
            sb.append("]}");
            sendJson(ex, sb.toString());
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    // 带密码验证设置抽屉温度
    private static void handleSetDrawerTempSecure(HttpExchange ex) {
        try {
            String body = readBody(ex);
            int id = Integer.parseInt(body.replaceAll(".*\"id\"[^0-9]*([0-9]+).*", "$1"));
            double temp = Double.parseDouble(body.replaceAll(".*\"temp\"[^0-9.-]*([0-9.-]+).*", "$1"));
            String encryptedPwd = body.replaceAll(".*\"encryptedPassword\"[^:]*:\"([^\"]+)\".*", "$1");
            int val = temp <= 0 ? 0 : (int)(temp * 10);

            byte[] pwdBytes = encryptedPwd.getBytes();
            int dataLen = 6 + pwdBytes.length;
            byte[] req = buildModbusReq(0x68, dataLen);
            req[8] = (byte)(id >> 8);
            req[9] = (byte)(id & 0xFF);
            req[10] = (byte)(val >> 8);
            req[11] = (byte)(val & 0xFF);
            System.arraycopy(pwdBytes, 0, req, 12, pwdBytes.length);

            byte[] resp = sendModbusRaw(req);
            boolean success = (resp[10] & 0xFF) == 0xFF;
            sendJson(ex, "{\"success\":" + success + "}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    // ========== 原有接口 ==========
    private static void initRedisPool() {
        try {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(10);
            config.setMaxIdle(5);
            config.setTestOnBorrow(true);
            jedisPool = new JedisPool(config, REDIS_HOST, REDIS_PORT, 2000);
        } catch (Exception e) {
            System.err.println("[HTTP网关] Redis连接池初始化失败: " + e.getMessage());
        }
    }

    private static Jedis getJedis() {
        try { return jedisPool.getResource(); }
        catch (Exception e) {
            System.err.println("[HTTP网关] Redis连接失败: " + e.getMessage());
            return null;
        }
    }

    private static void initDefaultThreshold() {
        try (Jedis jedis = getJedis()) {
            if (jedis != null) {
                if (!jedis.exists(KEY_ALARM_THRESHOLD_HIGH))
                    jedis.set(KEY_ALARM_THRESHOLD_HIGH, String.valueOf(DEFAULT_HIGH_THRESHOLD));
                if (!jedis.exists(KEY_ALARM_THRESHOLD_LOW))
                    jedis.set(KEY_ALARM_THRESHOLD_LOW, String.valueOf(DEFAULT_LOW_THRESHOLD));
            }
        } catch (Exception ignored) {}
    }

    private static byte[] modbusReq(byte func, int addr, int val) {
        byte[] req = new byte[12];
        req[0] = 0x00; req[1] = 0x01;
        req[2] = 0x00; req[3] = 0x00;
        req[4] = 0x00; req[5] = 0x06;
        req[6] = 0x01;
        req[7] = func;
        req[8] = (byte)(addr >> 8);
        req[9] = (byte)(addr & 0xFF);
        req[10] = (byte)(val >> 8);
        req[11] = (byte)(val & 0xFF);
        return req;
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
        ex.sendResponseHeaders(200, json.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readRealtimeStatusFromModbus() throws Exception {
        byte[] tempResp = sendModbusRaw(modbusReq((byte)0x04, 0, 1));
        double currentTemp = ((tempResp[9] & 0xFF) << 8 | (tempResp[10] & 0xFF)) / 10.0;
        byte[] setResp = sendModbusRaw(modbusReq((byte)0x03, 0, 1));
        double setTemp = ((setResp[9] & 0xFF) << 8 | (setResp[10] & 0xFF)) / 10.0;
        byte[] compResp = sendModbusRaw(modbusReq((byte)0x01, 0, 1));
        boolean compressorOn = (compResp[9] & 0x01) != 0;
        byte[] modeResp = sendModbusRaw(modbusReq((byte)0x01, 20, 1));
        boolean autoMode = (modeResp[9] & 0x01) != 0;
        return String.format(
            "{\"currentTemp\":%.1f,\"setTemp\":%.1f,\"compressorOn\":%b,\"autoMode\":%b,\"timestamp\":%d}",
            currentTemp, setTemp, compressorOn, autoMode, System.currentTimeMillis()
        );
    }

    private static void checkAlarm(double currentTemp) {
        try (Jedis jedis = getJedis()) {
            if (jedis == null) return;
            double high = Double.parseDouble(jedis.get(KEY_ALARM_THRESHOLD_HIGH));
            double low = Double.parseDouble(jedis.get(KEY_ALARM_THRESHOLD_LOW));
            String active = jedis.get(KEY_ALARM_ACTIVE);
            String type = "normal";
            if (currentTemp >= high) type = "high";
            else if (currentTemp <= low) type = "low";
            if (!type.equals(active)) {
                String time = sdf.format(new Date());
                String msg = "high".equals(type) ?
                    String.format("高温告警：当前温度 %.1f℃，超过阈值 %.1f℃", currentTemp, high) :
                    "low".equals(type) ?
                    String.format("低温告警：当前温度 %.1f℃，低于阈值 %.1f℃", currentTemp, low) :
                    String.format("温度恢复正常：当前温度 %.1f℃", currentTemp);
                String record = String.format(
                    "{\"time\":\"%s\",\"type\":\"%s\",\"temp\":%.1f,\"message\":\"%s\"}",
                    time, type, currentTemp, msg
                );
                jedis.lpush(KEY_ALARM_RECORDS, record);
                jedis.ltrim(KEY_ALARM_RECORDS, 0, MAX_ALARM_COUNT - 1);
                jedis.set(KEY_ALARM_ACTIVE, type);
                System.out.println("[告警] " + msg);
            }
        } catch (Exception ignored) {}
    }

    private static double extractTempFromJson(String json) {
        String key = "\"currentTemp\":";
        int s = json.indexOf(key) + key.length();
        int e = json.indexOf(",", s);
        return Double.parseDouble(json.substring(s, e));
    }

    private static void handleTemp(HttpExchange ex) {
        try {
            String status = null;
            try (Jedis jedis = getJedis()) {
                if (jedis != null) status = jedis.get(KEY_REALTIME_STATUS);
            } catch (Exception ignored) {}
            if (status == null) {
                status = readRealtimeStatusFromModbus();
                double t = extractTempFromJson(status);
                checkAlarm(t);
                try (Jedis jedis = getJedis()) {
                    if (jedis != null) {
                        jedis.setex(KEY_REALTIME_STATUS, CACHE_EXPIRE_SEC, status);
                        jedis.lpush(KEY_TEMP_HISTORY, status);
                        jedis.ltrim(KEY_TEMP_HISTORY, 0, MAX_HISTORY_COUNT - 1);
                    }
                } catch (Exception ignored) {}
            } else {
                checkAlarm(extractTempFromJson(status));
            }
            sendJson(ex, status);
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleSetTemp(HttpExchange ex) {
        try {
            String body = readBody(ex);
            double temp = Double.parseDouble(body.replaceAll("[^0-9.-]", ""));
            int val = (int)(temp * 10);
            sendModbusRaw(modbusReq((byte)0x06, 0, val));
            try (Jedis jedis = getJedis()) {
                if (jedis != null) jedis.del(KEY_REALTIME_STATUS);
            } catch (Exception ignored) {}
            sendJson(ex, "{\"success\":true}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleModeToggle(HttpExchange ex) {
        try {
            String body = readBody(ex);
            boolean auto = body.contains("true");
            sendModbusRaw(modbusReq((byte)0x05, 20, auto ? 0xFF00 : 0x0000));
            try (Jedis jedis = getJedis()) {
                if (jedis != null) jedis.del(KEY_REALTIME_STATUS);
            } catch (Exception ignored) {}
            sendJson(ex, "{\"success\":true}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleDrawers(HttpExchange ex) {
        try {
            byte[] resp = sendModbusRaw(modbusReq((byte)0x01, 1, 10));
            boolean[] states = new boolean[10];
            for (int i = 0; i < 10; i++) {
                int idx = 9 + i / 8;
                states[i] = (resp[idx] & (1 << (i % 8))) != 0;
            }
            StringBuilder sb = new StringBuilder("{\"states\":[");
            for (int i = 0; i < 10; i++) {
                sb.append(states[i]);
                if (i < 9) sb.append(",");
            }
            sb.append("]}");
            sendJson(ex, sb.toString());
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleDrawerControl(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            int id = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            String body = readBody(ex);
            boolean open = body.contains("true");
            sendModbusRaw(modbusReq((byte)0x05, id, open ? 0xFF00 : 0x0000));
            sendJson(ex, "{\"success\":true}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleDrawersTemp(HttpExchange ex) {
        try {
            byte[] currResp = sendModbusRaw(modbusReq((byte)0x04, 1, 10));
            byte[] setResp = sendModbusRaw(modbusReq((byte)0x03, 1, 10));
            StringBuilder sb = new StringBuilder("{\"drawers\":[");
            for (int i = 0; i < 10; i++) {
                int c = ((currResp[9+2*i] & 0xFF) << 8) | (currResp[10+2*i] & 0xFF);
                double ct = c / 10.0;
                int s = ((setResp[9+2*i] & 0xFF) << 8) | (setResp[10+2*i] & 0xFF);
                double st = s / 10.0;
                boolean def = s == 0;
                sb.append(String.format(
                    "{\"id\":%d,\"currentTemp\":%.1f,\"setTemp\":%.1f,\"useDefault\":%b}",
                    i+1, ct, st, def
                ));
                if (i < 9) sb.append(",");
            }
            sb.append("]}");
            sendJson(ex, sb.toString());
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleSetDrawerTemp(HttpExchange ex) {
        try {
            String body = readBody(ex);
            int id = Integer.parseInt(body.replaceAll(".*\"id\"[^0-9]*([0-9]+).*", "$1"));
            double temp = Double.parseDouble(body.replaceAll(".*\"temp\"[^0-9.-]*([0-9.-]+).*", "$1"));
            int val = temp <= 0 ? 0 : (int)(temp * 10);
            sendModbusRaw(modbusReq((byte)0x06, id, val));
            sendJson(ex, "{\"success\":true}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}"); }
            catch (IOException ignored) {}
        }
    }

    private static void handleTempHistory(HttpExchange ex) {
        try {
            String res = "[]";
            try (Jedis jedis = getJedis()) {
                if (jedis != null) {
                    var list = jedis.lrange(KEY_TEMP_HISTORY, 0, -1);
                    java.util.Collections.reverse(list);
                    res = "[" + String.join(",", list) + "]";
                }
            }
            sendJson(ex, res);
        } catch (Exception e) {
            try { sendJson(ex, "[]"); } catch (IOException ignored) {}
        }
    }

    private static void handleAlarmList(HttpExchange ex) {
        try {
            String res = "[]";
            try (Jedis jedis = getJedis()) {
                if (jedis != null) {
                    var list = jedis.lrange(KEY_ALARM_RECORDS, 0, -1);
                    res = "[" + String.join(",", list) + "]";
                }
            }
            sendJson(ex, res);
        } catch (Exception e) {
            try { sendJson(ex, "[]"); } catch (IOException ignored) {}
        }
    }

    private static void handleAlarmThreshold(HttpExchange ex) {
        try {
            String body = readBody(ex);
            double high = Double.parseDouble(body.replaceAll(".*\"high\"[^0-9.-]*([0-9.-]+).*", "$1"));
            double low = Double.parseDouble(body.replaceAll(".*\"low\"[^0-9.-]*([0-9.-]+).*", "$1"));
            try (Jedis jedis = getJedis()) {
                if (jedis != null) {
                    jedis.set(KEY_ALARM_THRESHOLD_HIGH, String.valueOf(high));
                    jedis.set(KEY_ALARM_THRESHOLD_LOW, String.valueOf(low));
                    jedis.del(KEY_ALARM_ACTIVE);
                }
            }
            sendJson(ex, "{\"success\":true}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false,\"msg\":\"参数错误\"}"); } catch (IOException ignored) {}
        }
    }

    private static void handleAlarmClear(HttpExchange ex) {
        try {
            try (Jedis jedis = getJedis()) {
                if (jedis != null) {
                    jedis.del(KEY_ALARM_RECORDS);
                    jedis.set(KEY_ALARM_ACTIVE, "normal");
                }
            }
            sendJson(ex, "{\"success\":true}");
        } catch (Exception e) {
            try { sendJson(ex, "{\"success\":false}"); } catch (IOException ignored) {}
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
