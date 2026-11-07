import com.sun.net.httpserver.*;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class SoftwareA {
    private boolean isHeadless = false;
    private double currentTemp = 25.0;
    private double setTemp = 10.0;
    private boolean compressorOn = false;
    private boolean[] drawerStatus = new boolean[10];
    private double[] drawerSetTemp = new double[10];
    private double[] drawerCurrentTemp = new double[10];
    private boolean autoMode = true;
    private ModbusServer modbusServer = new ModbusServer();
    
    // GUI 组件（无头模式下为 null）
    private JLabel lblCurrentTemp, lblSetTemp, lblCompressor, lblMode;
    private JToggleButton[] btnDrawers = new JToggleButton[10];
    private JTextField txtSetTemp;
    private JButton btnModeToggle;

    private final List<Map<String, Object>> alarmList = new ArrayList<>();
    private String lastAlarmType = null;

    public static void main(String[] args) {
        SoftwareA app = new SoftwareA();
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--headless") || arg.equalsIgnoreCase("-t")) {
                app.isHeadless = true;
            }
        }
        
        if (app.isHeadless) {
            app.startHeadless();
        } else {
            SwingUtilities.invokeLater(() -> app.createAndShowGUI());
        }
    }

    // 终端无头模式启动
    private void startHeadless() {
        System.out.println(">>> 启动 SoftwareA (无头终端模式) <<<");
        initServices();
        startBackgroundTasks();
        
        // 保持主线程存活
        try { Thread.currentThread().join(); } catch (InterruptedException e) {}
    }

    // 提取服务启动逻辑，供 GUI 和 无头模式共用
    private void initServices() {
        modbusServer.holdingRegisters[0] = (int)(setTemp * 10);
        modbusServer.inputRegisters[0] = (int)(currentTemp * 10);
        modbusServer.coils[20] = autoMode;
        for (int i = 0; i < 10; i++) {
            drawerCurrentTemp[i] = currentTemp;
            drawerSetTemp[i] = 0;
            modbusServer.holdingRegisters[i + 1] = 0;
            modbusServer.inputRegisters[i + 1] = (int)(currentTemp * 10);
            modbusServer.discreteInputs[i + 1] = false;
        }

        try {
            modbusServer.start(5020);
            System.out.println("[服务端] Modbus TCP 服务已启动，监听端口 5020");
        } catch (Exception e) {
            System.err.println("Modbus启动失败: " + e.getMessage());
            System.exit(1);
        }

        try {
            startHttpServer();
        } catch (IOException e) {
            System.err.println("HTTP服务启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createAndShowGUI() {
        initServices();

        JFrame frame = new JFrame("软件A：本地快递柜终端 (Modbus + HTTP API)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(520, 700);
        frame.setLayout(new BorderLayout(10, 10));

        JPanel topPanel = new JPanel(new GridLayout(5, 2, 5, 5));
        topPanel.setBorder(BorderFactory.createTitledBorder("温度与压缩机控制"));

        lblCurrentTemp = new JLabel("25.0 ℃");
        lblCurrentTemp.setFont(new Font("微软雅黑", Font.BOLD, 16));
        lblCurrentTemp.setForeground(Color.RED);
        topPanel.add(new JLabel("当前温度:"));
        topPanel.add(lblCurrentTemp);

        JPanel setPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        txtSetTemp = new JTextField("10.0", 5);
        JButton btnSet = new JButton("修改设定");
        btnSet.addActionListener(e -> {
            try {
                setTemp = Double.parseDouble(txtSetTemp.getText());
                modbusServer.holdingRegisters[0] = (int)(setTemp * 10);
                lblSetTemp.setText(setTemp + " ℃");
                if (!autoMode) {
                    autoMode = true;
                    modbusServer.coils[20] = true;
                    updateModeUI();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "请输入有效数字");
            }
        });
        setPanel.add(txtSetTemp);
        setPanel.add(btnSet);
        topPanel.add(new JLabel("设定温度:"));
        topPanel.add(setPanel);

        lblSetTemp = new JLabel("10.0 ℃");
        topPanel.add(new JLabel("目标设定温度:"));
        topPanel.add(lblSetTemp);

        lblCompressor = new JLabel("关闭 (回温中)");
        topPanel.add(new JLabel("压缩机状态:"));
        topPanel.add(lblCompressor);

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lblMode = new JLabel("自动控温");
        lblMode.setForeground(new Color(0, 128, 0));
        btnModeToggle = new JButton("切换为手动");
        btnModeToggle.addActionListener(e -> {
            autoMode = !autoMode;
            modbusServer.coils[20] = autoMode;
            updateModeUI();
        });
        modePanel.add(lblMode);
        modePanel.add(btnModeToggle);
        topPanel.add(new JLabel("控制模式:"));
        topPanel.add(modePanel);

        JPanel drawerPanel = new JPanel(new GridLayout(2, 5, 5, 5));
        drawerPanel.setBorder(BorderFactory.createTitledBorder("10个抽屉控制 (右键菜单)"));
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            btnDrawers[i] = new JToggleButton();
            btnDrawers[i].addActionListener(e -> {
                if (modbusServer.isDrawerLocked(idx) && !btnDrawers[idx].isSelected()) {
                    String pwd = JOptionPane.showInputDialog(frame, "请输入抽屉密码：");
                    if (pwd == null || !modbusServer.verifyDrawerPasswordPlain(idx, pwd)) {
                        JOptionPane.showMessageDialog(frame, "密码错误，无法开锁");
                        btnDrawers[idx].setSelected(false);
                        return;
                    }
                }
                drawerStatus[idx] = btnDrawers[idx].isSelected();
                modbusServer.coils[idx + 1] = drawerStatus[idx];
                modbusServer.discreteInputs[idx + 1] = drawerStatus[idx];
                updateDrawerUI();
            });

            JPopupMenu popup = new JPopupMenu();
            JMenuItem setPwdItem = new JMenuItem("设置密码");
            setPwdItem.addActionListener(e -> showSetPasswordDialog(idx));
            JMenuItem setTempItem = new JMenuItem("设置温度");
            setTempItem.addActionListener(e -> showSetDrawerTempDialog(idx));
            popup.add(setPwdItem);
            popup.add(setTempItem);
            btnDrawers[i].setComponentPopupMenu(popup);
            drawerPanel.add(btnDrawers[i]);
        }

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(drawerPanel, BorderLayout.CENTER);

        JLabel tip = new JLabel("Modbus TCP:5020 | HTTP API:8090", SwingConstants.CENTER);
        tip.setForeground(Color.BLUE);
        frame.add(tip, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        updateDrawerUI();
        startBackgroundTasks();
    }

    private void startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8090), 0);
        server.createContext("/api/temp", new TempHandler());
        server.createContext("/api/temp/set", new SetTempHandler());
        server.createContext("/api/drawers", new DrawersHandler());
        server.createContext("/api/drawers/lockstatus", new LockStatusHandler());
        server.createContext("/api/drawers/temp", new DrawerTempHandler());
        server.createContext("/api/drawers/temp/set", new SetDrawerTempHandler());
        server.createContext("/api/drawers/password/set", new SetPasswordHandler());
        server.createContext("/api/drawers/password/verify", new VerifyPasswordHandler());
        server.createContext("/api/drawers/publickey", new PublicKeyHandler());
        server.createContext("/api/mode/toggle", new ModeToggleHandler());
        server.createContext("/api/alarms", new AlarmsHandler());
        server.createContext("/api/alarms/clear", new AlarmsClearHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[HTTP] API 服务已启动，监听端口 8090");
    }

    private abstract class BaseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            try {
                handleRequest(exchange);
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, "{\"error\":\"Internal Server Error\"}");
            }
        }
        abstract void handleRequest(HttpExchange exchange) throws IOException;
    }

    private void sendJson(HttpExchange exchange, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream is = exchange.getRequestBody();
        byte[] data = new byte[1024];
        int n;
        while ((n = is.read(data)) != -1) buffer.write(data, 0, n);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end == -1) return null;
            return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-' || json.charAt(end) == 'e' || json.charAt(end) == 'E')) end++;
            return json.substring(start, end);
        }
    }

    class TempHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String json = String.format("{\"currentTemp\":%.1f,\"setTemp\":%.1f,\"compressorOn\":%b,\"autoMode\":%b}",
                    currentTemp, setTemp, compressorOn, autoMode);
            sendJson(exchange, json);
        }
    }

    class SetTempHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String body = readBody(exchange);
            String tempStr = extractJsonValue(body, "temp");
            if (tempStr == null) { sendJson(exchange, "{\"success\":false,\"message\":\"参数错误\"}"); return; }
            double temp = Double.parseDouble(tempStr);
            setTemp = temp;
            modbusServer.holdingRegisters[0] = (int)(setTemp * 10);
            if (!isHeadless) {
                SwingUtilities.invokeLater(() -> {
                    lblSetTemp.setText(String.format("%.1f ℃", setTemp));
                    txtSetTemp.setText(String.format("%.1f", setTemp));
                });
            }
            sendJson(exchange, "{\"success\":true}");
        }
    }

    class DrawersHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod().toUpperCase();
            if ("GET".equals(method)) {
                StringBuilder states = new StringBuilder("[");
                for (int i = 0; i < 10; i++) { if (i > 0) states.append(","); states.append(drawerStatus[i]); }
                states.append("]");
                sendJson(exchange, "{\"states\":" + states + "}");
            } else if ("POST".equals(method)) {
                String path = exchange.getRequestURI().getPath();
                try {
                    int id = Integer.parseInt(path.substring("/api/drawers/".length()));
                    String body = readBody(exchange);
                    boolean open = body.contains("\"open\":true");
                    drawerStatus[id - 1] = open;
                    modbusServer.coils[id] = open;
                    modbusServer.discreteInputs[id] = open;
                    sendJson(exchange, "{\"success\":true}");
                } catch (Exception e) { sendJson(exchange, "{\"success\":false,\"message\":\"无效ID\"}"); }
            } else exchange.sendResponseHeaders(405, -1);
        }
    }

    class LockStatusHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < 10; i++) {
                if (i > 0) sb.append(",");
                boolean locked = modbusServer.isDrawerLocked(i);
                int hours = modbusServer.getRemainingHours(i);
                sb.append(String.format("{\"locked\":%b,\"remainHours\":%d}", locked, hours));
            }
            sb.append("]");
            sendJson(exchange, "{\"drawers\":" + sb + "}");
        }
    }

    class DrawerTempHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < 10; i++) {
                if (i > 0) sb.append(",");
                boolean useDefault = drawerSetTemp[i] == 0;
                sb.append(String.format("{\"currentTemp\":%.1f,\"setTemp\":%.1f,\"useDefault\":%b}",
                        drawerCurrentTemp[i], drawerSetTemp[i], useDefault));
            }
            sb.append("]");
            sendJson(exchange, "{\"drawers\":" + sb + "}");
        }
    }

    class SetDrawerTempHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String body = readBody(exchange);
            String idStr = extractJsonValue(body, "id");
            String tempStr = extractJsonValue(body, "temp");
            String encPwd = extractJsonValue(body, "encryptedPassword");
            if (idStr == null || tempStr == null) { sendJson(exchange, "{\"success\":false,\"message\":\"参数错误\"}"); return; }
            int id = Integer.parseInt(idStr);
            double temp = Double.parseDouble(tempStr);
            if (modbusServer.isDrawerLocked(id - 1)) {
                if (encPwd == null || encPwd.isEmpty() || "null".equals(encPwd)) {
                    sendJson(exchange, "{\"success\":false,\"message\":\"抽屉已锁定，需要密码\"}"); return;
                }
                if (!modbusServer.verifyDrawerPassword(id - 1, encPwd)) {
                    sendJson(exchange, "{\"success\":false,\"message\":\"密码错误\"}"); return;
                }
            }
            drawerSetTemp[id - 1] = temp;
            modbusServer.holdingRegisters[id] = (int)(temp * 10);
            sendJson(exchange, "{\"success\":true}");
        }
    }

    class SetPasswordHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String body = readBody(exchange);
            String idStr = extractJsonValue(body, "id");
            String encPwd = extractJsonValue(body, "encryptedPassword");
            String daysStr = extractJsonValue(body, "days");
            if (idStr == null || encPwd == null || daysStr == null) { sendJson(exchange, "{\"success\":false,\"message\":\"参数错误\"}"); return; }
            int id = Integer.parseInt(idStr);
            int days = Integer.parseInt(daysStr);
            boolean ok = modbusServer.setDrawerPassword(id - 1, encPwd, days);
            sendJson(exchange, "{\"success\":" + ok + "}");
        }
    }

    class VerifyPasswordHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String body = readBody(exchange);
            String idStr = extractJsonValue(body, "id");
            String encPwd = extractJsonValue(body, "encryptedPassword");
            if (idStr == null || encPwd == null) { sendJson(exchange, "{\"success\":false,\"message\":\"参数错误\"}"); return; }
            int id = Integer.parseInt(idStr);
            boolean ok = modbusServer.verifyDrawerPassword(id - 1, encPwd);
            sendJson(exchange, "{\"success\":" + ok + "}");
        }
    }

    class PublicKeyHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            String key = Base64.getEncoder().encodeToString(modbusServer.getPublicKey().getEncoded());
            sendJson(exchange, "{\"publicKey\":\"" + key + "\"}");
        }
    }

    class ModeToggleHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            autoMode = !autoMode;
            modbusServer.coils[20] = autoMode;
            sendJson(exchange, "{\"success\":true,\"autoMode\":" + autoMode + "}");
        }
    }

    class AlarmsHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < alarmList.size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> alarm = alarmList.get(i);
                sb.append(String.format("{\"time\":\"%s\",\"type\":\"%s\",\"message\":\"%s\"}",
                        alarm.get("time"), alarm.get("type"), alarm.get("message")));
            }
            sb.append("]");
            sendJson(exchange, "{\"alarms\":" + sb + "}");
        }
    }

    class AlarmsClearHandler extends BaseHandler {
        void handleRequest(HttpExchange exchange) throws IOException {
            alarmList.clear();
            sendJson(exchange, "{\"success\":true}");
        }
    }

    private void checkAlarms(double temp) {
        double high = 20.0, low = 0.0;
        String type = null;
        if (temp >= high) type = "high";
        else if (temp <= low) type = "low";
        if (type != null && !type.equals(getLastAlarmType())) {
            Map<String, Object> alarm = new HashMap<>();
            alarm.put("time", new java.text.SimpleDateFormat("HH:mm:ss").format(new Date()));
            alarm.put("type", type);
            alarm.put("message", String.format("温度%.1f℃触发%s告警", temp, type.equals("high") ? "高温" : "低温"));
            alarmList.add(alarm);
            if (alarmList.size() > 100) alarmList.remove(0);
        }
        if (type == null && getLastAlarmType() != null) {
            Map<String, Object> alarm = new HashMap<>();
            alarm.put("time", new java.text.SimpleDateFormat("HH:mm:ss").format(new Date()));
            alarm.put("type", "normal");
            alarm.put("message", "温度恢复正常");
            alarmList.add(alarm);
        }
    }

    private String getLastAlarmType() { 
        if (alarmList.isEmpty()) return null; 
        return (String) alarmList.get(alarmList.size() - 1).get("type"); 
    }

    private void startBackgroundTasks() {
        javax.swing.Timer timer = new javax.swing.Timer(1000, e -> {
            double newSetTemp = modbusServer.holdingRegisters[0] / 10.0;
            if (Math.abs(newSetTemp - setTemp) > 0.01) {
                setTemp = newSetTemp;
                if (!isHeadless) {
                    lblSetTemp.setText(String.format("%.1f ℃", setTemp));
                    txtSetTemp.setText(String.format("%.1f", setTemp));
                }
            }
            for (int i = 0; i < 10; i++) {
                double remoteSet = modbusServer.holdingRegisters[i + 1] / 10.0;
                if (Math.abs(remoteSet - drawerSetTemp[i]) > 0.01) drawerSetTemp[i] = remoteSet;
            }
            boolean remoteAuto = modbusServer.coils[20];
            if (remoteAuto != autoMode) { 
                autoMode = remoteAuto; 
                if (!isHeadless) updateModeUI(); 
            }
            
            if (autoMode) {
                if (currentTemp > setTemp + 0.5) compressorOn = true;
                else if (currentTemp < setTemp - 0.5) compressorOn = false;
                modbusServer.coils[0] = compressorOn;
                modbusServer.discreteInputs[0] = compressorOn;
            }
            if (compressorOn) currentTemp -= 0.2; else currentTemp += 0.15;
            currentTemp = Math.max(-10, Math.min(60, currentTemp));
            modbusServer.inputRegisters[0] = (int)(currentTemp * 10);
            
            for (int i = 0; i < 10; i++) {
                if (drawerStatus[i]) drawerCurrentTemp[i] += 0.1; else drawerCurrentTemp[i] = currentTemp;
                drawerCurrentTemp[i] = Math.min(60, drawerCurrentTemp[i]);
                modbusServer.inputRegisters[i + 1] = (int)(drawerCurrentTemp[i] * 10);
                modbusServer.holdingRegisters[i + 1] = (int)(drawerSetTemp[i] * 10);
            }
            for (int i = 0; i < 10; i++) {
                boolean remoteDrawer = modbusServer.coils[i + 1];
                if (remoteDrawer != drawerStatus[i]) {
                    drawerStatus[i] = remoteDrawer;
                    if (!isHeadless) btnDrawers[i].setSelected(drawerStatus[i]);
                }
            }
            checkAlarms(currentTemp);
            
            if (isHeadless) {
                // 无头模式下，每 10 秒在终端打印一次当前状态
                if (System.currentTimeMillis() % 10000 < 1000) {
                    System.out.printf("[终端监控] 当前温度: %.1f℃ | 设定温度: %.1f℃ | 压缩机: %s\n", 
                        currentTemp, setTemp, compressorOn ? "开" : "关");
                }
            } else {
                updateUI();
            }
        });
        timer.start();
    }

    private void updateUI() {
        SwingUtilities.invokeLater(() -> {
            lblCurrentTemp.setText(String.format("%.1f ℃", currentTemp));
            lblCompressor.setText(compressorOn ? "开启 (制冷中)" : "关闭 (回温中)");
            lblCompressor.setForeground(compressorOn ? Color.BLUE : Color.GRAY);
            updateDrawerUI();
        });
    }

    private void updateDrawerUI() {
        for (int i = 0; i < 10; i++) {
            String lockIcon = modbusServer.isDrawerLocked(i) ? "🔒 " : "";
            btnDrawers[i].setText("<html>" + lockIcon + "抽屉 " + (i + 1) +
                    "<br><small>" + String.format("%.1f℃", drawerCurrentTemp[i]) + "</small></html>");
        }
    }

    private void updateModeUI() {
        if (autoMode) { lblMode.setText("自动控温"); lblMode.setForeground(new Color(0, 128, 0)); btnModeToggle.setText("切换为手动"); }
        else { lblMode.setText("手动控制"); lblMode.setForeground(Color.RED); btnModeToggle.setText("切换为自动"); }
    }

    // 界面弹窗相关逻辑（无头模式下用不到，保留以保证GUI模式完整）
    private void showSetPasswordDialog(int index) {
        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        JPasswordField pwdField = new JPasswordField();
        JPasswordField confirmField = new JPasswordField();
        JTextField daysField = new JTextField("7");
        panel.add(new JLabel("新密码:")); panel.add(pwdField);
        panel.add(new JLabel("确认密码:")); panel.add(confirmField);
        panel.add(new JLabel("有效期(天):")); panel.add(daysField);

        int result = JOptionPane.showConfirmDialog(null, panel, "设置抽屉 " + (index + 1) + " 密码", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            String pwd = new String(pwdField.getPassword());
            String confirm = new String(confirmField.getPassword());
            if (!pwd.equals(confirm)) { JOptionPane.showMessageDialog(null, "两次密码不一致"); return; }
            int days;
            try { days = Integer.parseInt(daysField.getText()); if (days < 1 || days > 7) throw new NumberFormatException(); }
            catch (NumberFormatException ex) { JOptionPane.showMessageDialog(null, "有效期必须为1~7的整数"); return; }
            boolean ok = modbusServer.setDrawerPasswordPlain(index, pwd, days);
            JOptionPane.showMessageDialog(null, ok ? "密码设置成功" : "密码设置失败");
            updateDrawerUI();
        }
    }

    private void showSetDrawerTempDialog(int index) {
        String tempStr = JOptionPane.showInputDialog(null, "设置抽屉 " + (index + 1) + " 温度 (℃):");
        if (tempStr == null) return;
        double temp;
        try { temp = Double.parseDouble(tempStr); }
        catch (NumberFormatException ex) { JOptionPane.showMessageDialog(null, "无效的温度值"); return; }

        if (modbusServer.isDrawerLocked(index)) {
            String pwd = JOptionPane.showInputDialog(null, "抽屉已锁定，请输入密码：");
            if (pwd == null) return;
            if (!modbusServer.verifyDrawerPasswordPlain(index, pwd)) { JOptionPane.showMessageDialog(null, "密码错误"); return; }
        }
        drawerSetTemp[index] = temp;
        modbusServer.holdingRegisters[index + 1] = (int)(temp * 10);
        updateDrawerUI();
        JOptionPane.showMessageDialog(null, "温度设置成功");
    }
}
