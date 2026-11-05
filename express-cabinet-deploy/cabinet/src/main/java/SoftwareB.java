import javax.swing.*;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;

public class SoftwareB {
    private boolean isHeadless = false;
    
    private XYSeries tempSeries = new XYSeries("实时温度");
    private int timeCounter = 0;
    private JLabel lblStatus, lblCurrentTemp, lblSetTemp;
    private JToggleButton[] btnDrawers = new JToggleButton[10];
    private JTextField txtSetTemp;
    private JButton btnModeToggle;
    private boolean remoteAuto = true;

    private boolean[] drawerLocked = new boolean[10];
    private int[] remainHours = new int[10];
    private double[] drawerSetTemp = new double[10];
    private PublicKey serverPublicKey;

    public static void main(String[] args) {
        SoftwareB app = new SoftwareB();
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
        System.out.println(">>> 启动 SoftwareB (无头终端模式) <<<");
        new Thread(() -> { testConnection(); fetchPublicKey(); }).start();
        
        while (true) {
            readDataFromServer();
            try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
        }
    }

    private void createAndShowGUI() {
        JFrame frame = new JFrame("软件B：远程监控客户端 (Modbus 客户端)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(950, 650);
        frame.setLayout(new BorderLayout(10, 10));

        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setBorder(BorderFactory.createTitledBorder("远程控制中心"));
        controlPanel.setPreferredSize(new Dimension(280, 0));

        lblStatus = new JLabel("状态: 未连接");
        lblStatus.setForeground(Color.RED);
        controlPanel.add(lblStatus);
        controlPanel.add(Box.createVerticalStrut(15));

        lblSetTemp = new JLabel("当前设定温度: -- ℃");
        lblSetTemp.setFont(new Font("微软雅黑", Font.BOLD, 14));
        lblSetTemp.setForeground(new Color(0, 102, 204));
        controlPanel.add(lblSetTemp);
        controlPanel.add(Box.createVerticalStrut(15));

        controlPanel.add(new JLabel("远程修改设定温度:"));
        JPanel pTemp = new JPanel(new FlowLayout(FlowLayout.LEFT));
        txtSetTemp = new JTextField("10.0", 5);
        JButton btnSetTemp = new JButton("下发设定");
        btnSetTemp.addActionListener(e -> writeSetTemp());
        pTemp.add(txtSetTemp); pTemp.add(btnSetTemp);
        controlPanel.add(pTemp);
        controlPanel.add(Box.createVerticalStrut(15));

        lblCurrentTemp = new JLabel("当前温度: -- ℃");
        lblCurrentTemp.setFont(new Font("微软雅黑", Font.BOLD, 14));
        controlPanel.add(lblCurrentTemp);
        controlPanel.add(Box.createVerticalStrut(15));

        controlPanel.add(new JLabel("控制模式:"));
        btnModeToggle = new JButton("切换为手动模式");
        btnModeToggle.addActionListener(e -> toggleAutoMode());
        controlPanel.add(btnModeToggle);
        controlPanel.add(Box.createVerticalStrut(15));

        controlPanel.add(new JLabel("远程开关抽屉 (右键设置密码/温度)"));
        JPanel drawerGrid = new JPanel(new GridLayout(5, 2, 5, 5));
        for (int i = 0; i < 10; i++) {
            final int index = i;
            btnDrawers[i] = new JToggleButton("抽屉 " + (i + 1));
            btnDrawers[i].addActionListener(e -> handleDrawerToggle(index));
            JPopupMenu popup = new JPopupMenu();
            JMenuItem setPwdItem = new JMenuItem("设置密码");
            setPwdItem.addActionListener(e -> showSetPasswordDialog(index));
            JMenuItem setTempItem = new JMenuItem("设置温度");
            setTempItem.addActionListener(e -> showSetDrawerTempDialog(index));
            popup.add(setPwdItem);
            popup.add(setTempItem);
            btnDrawers[i].setComponentPopupMenu(popup);
            drawerGrid.add(btnDrawers[i]);
        }
        controlPanel.add(drawerGrid);

        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(tempSeries);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "快递柜实时温度曲线", "时间 (秒)", "温度 (℃)", dataset);
        ChartPanel chartPanel = new ChartPanel(chart);

        frame.add(controlPanel, BorderLayout.WEST);
        frame.add(chartPanel, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        new Thread(() -> { testConnection(); fetchPublicKey(); }).start();
        javax.swing.Timer pollTimer = new javax.swing.Timer(3000, e -> readDataFromServer());
        pollTimer.start();
    }

    private byte[] modbusReq(byte function, int address, int valueOrCount) {
        byte[] req = new byte[12];
        req[0] = 0x00; req[1] = 0x01; req[2] = 0x00; req[3] = 0x00;
        req[4] = 0x00; req[5] = 0x06; req[6] = 0x01;
        req[7] = function;
        req[8] = (byte)(address >> 8); req[9] = (byte)(address & 0xFF);
        req[10] = (byte)(valueOrCount >> 8); req[11] = (byte)(valueOrCount & 0xFF);
        return req;
    }

    private byte[] modbusReqWithData(byte function, int drawerId, byte[] data) {
        int totalLen = 6 + data.length;
        byte[] req = new byte[12 + data.length];
        req[0] = 0x00; req[1] = 0x01; req[2] = 0x00; req[3] = 0x00;
        req[4] = (byte)(totalLen >> 8); req[5] = (byte)(totalLen & 0xFF);
        req[6] = 0x01; req[7] = function;
        req[8] = (byte)(drawerId >> 8); req[9] = (byte)(drawerId & 0xFF);
        System.arraycopy(data, 0, req, 12, data.length);
        return req;
    }

    private byte[] readModbusResponse(InputStream in) throws IOException {
        byte[] header = new byte[6];
        if (in.read(header) < 6) return null;
        int len = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
        byte[] data = new byte[len + 6];
        System.arraycopy(header, 0, data, 0, 6);
        if (in.read(data, 6, len) < len) return null;
        return data;
    }

    private void fetchPublicKey() {
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {
            byte[] req = modbusReq((byte)0x67, 0, 0);
            out.write(req);
            byte[] resp = readModbusResponse(in);
            if (resp != null && resp.length > 8) {
                int dataLen = ((resp[4] & 0xFF) << 8) | (resp[5] & 0xFF);
                int keyLen = dataLen - 2;
                byte[] keyBytes = new byte[keyLen];
                System.arraycopy(resp, 8, keyBytes, 0, keyLen);
                String keyStr = new String(keyBytes, StandardCharsets.UTF_8);
                byte[] decoded = Base64.getDecoder().decode(keyStr);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                serverPublicKey = keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
                System.out.println("[客户端] 成功获取服务器公钥");
            }
        } catch (Exception e) {
            System.err.println("[客户端] 获取公钥失败: " + e.getMessage());
        }
    }

    private String encryptPassword(String plainPwd) {
        try {
            if (serverPublicKey == null) {
                if (!isHeadless) JOptionPane.showMessageDialog(null, "公钥未就绪，请稍后重试");
                return null;
            }
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, serverPublicKey);
            byte[] encrypted = cipher.doFinal(plainPwd.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            if (!isHeadless) JOptionPane.showMessageDialog(null, "加密失败: " + e.getMessage());
            return null;
        }
    }

    // ========== 核心轮询逻辑 ==========
    private void readDataFromServer() {
        readTemperature();
        readSetTemp();
        readDrawerStatus();
        readAutoMode();
        readLockStatus();
        readDrawerSetTemp();
    }

    private void readTemperature() {
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {
            out.write(modbusReq((byte)0x04, 0, 1));
            byte[] resp = readModbusResponse(in);
            if (resp != null && resp.length >= 11) {
                double temp = ((resp[9] & 0xFF) << 8 | (resp[10] & 0xFF)) / 10.0;
                timeCounter += 3;
                if (!isHeadless) {
                    lblCurrentTemp.setText(String.format("当前温度: %.1f ℃", temp));
                    tempSeries.add(timeCounter, temp);
                    if (tempSeries.getItemCount() > 50) tempSeries.remove(0);
                } else {
                    System.out.printf("[%s] [终端监控] 当前温度: %.1f ℃\n", 
                        new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()), temp);
                }
            }
        } catch (Exception e) {
            if (!isHeadless) {
                lblStatus.setText("状态: 连接失败"); 
                lblStatus.setForeground(Color.RED);
            } else {
                System.err.println("[终端监控] 连接服务端失败: " + e.getMessage());
            }
        }
    }

    private void readSetTemp() {
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {
            out.write(modbusReq((byte)0x03, 0, 1));
            byte[] resp = readModbusResponse(in);
            if (resp != null && resp.length >= 11) {
                double setTemp = ((resp[9] & 0xFF) << 8 | (resp[10] & 0xFF)) / 10.0;
                if (!isHeadless) lblSetTemp.setText(String.format("当前设定温度: %.1f ℃", setTemp));
            }
        } catch (Exception e) { }
    }

    private void readDrawerStatus() {
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {
            out.write(modbusReq((byte)0x01, 1, 10));
            byte[] resp = readModbusResponse(in);
            if (resp != null && resp.length >= 9) {
                for (int i = 0; i < 10; i++) {
                    int byteIndex = 9 + i / 8;
                    if (byteIndex < resp.length) {
                        int bitMask = 1 << (i % 8);
                        boolean state = (resp[byteIndex] & bitMask) != 0;
                        if (!isHeadless) btnDrawers[i].setSelected(state);
                    }
                }
            }
        } catch (Exception e) { }
    }

    private void readAutoMode() {
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {
            out.write(modbusReq((byte)0x01, 20, 1));
            byte[] resp = readModbusResponse(in);
            if (resp != null && resp.length >= 10) {
                remoteAuto = (resp[9] & 0x01) != 0;
                if (!isHeadless) updateModeButton();
            }
        } catch (Exception e) { }
    }

    private void readLockStatus() {
        for (int i = 0; i < 10; i++) {
            try (Socket sock = new Socket("127.0.0.1", 5020);
                 OutputStream out = sock.getOutputStream();
                 InputStream in = sock.getInputStream()) {
                out.write(modbusReq((byte)0x66, i + 1, 0));
                byte[] resp = readModbusResponse(in);
                if (resp != null && resp.length >= 11) {
                    drawerLocked[i] = (resp[8] & 0xFF) == 0xFF;
                    remainHours[i] = ((resp[9] & 0xFF) << 8) | (resp[10] & 0xFF);
                }
            } catch (Exception e) { }
        }
        if (!isHeadless) for (int i = 0; i < 10; i++) updateDrawerButtonText(i);
    }

    private void readDrawerSetTemp() {
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {
            out.write(modbusReq((byte)0x03, 1, 10));
            byte[] resp = readModbusResponse(in);
            if (resp != null && resp.length >= 9 + 20) {
                for (int i = 0; i < 10; i++) {
                    int base = 9 + i * 2;
                    int raw = ((resp[base] & 0xFF) << 8) | (resp[base + 1] & 0xFF);
                    drawerSetTemp[i] = raw / 10.0;
                }
                if (!isHeadless) for (int i = 0; i < 10; i++) updateDrawerButtonText(i);
            }
        } catch (Exception e) { }
    }

    // --- GUI 辅助方法（无头模式下不会调用） ---
    private void updateDrawerButtonText(int index) {
        String lockIcon = drawerLocked[index] ? "🔒 " : "";
        double setTemp = drawerSetTemp[index];
        String tempText = setTemp != 0 ? String.format(" 设定%.1f℃", setTemp) : " 默认";
        btnDrawers[index].setText(lockIcon + "抽屉 " + (index + 1) + tempText);
    }

    private void updateModeButton() {
        btnModeToggle.setText(remoteAuto ? "切换为手动模式" : "切换为自动模式");
    }

    private void toggleAutoMode() {
        remoteAuto = !remoteAuto;
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream()) {
            out.write(modbusReq((byte)0x05, 20, remoteAuto ? 0xFF00 : 0x0000));
            updateModeButton();
        } catch (Exception e) { JOptionPane.showMessageDialog(null, "模式切换失败"); }
    }

    private void writeSetTemp() {
        try {
            double temp = Double.parseDouble(txtSetTemp.getText());
            int val = (int)(temp * 10);
            try (Socket sock = new Socket("127.0.0.1", 5020);
                 OutputStream out = sock.getOutputStream()) {
                out.write(modbusReq((byte)0x06, 0, val));
                JOptionPane.showMessageDialog(null, "设定温度下发成功");
            }
        } catch (Exception e) { JOptionPane.showMessageDialog(null, "下发失败: " + e.getMessage()); }
    }

    private void testConnection() {
        try (Socket s = new Socket("127.0.0.1", 5020)) {
            if (!isHeadless) {
                lblStatus.setText("状态: 已连接到软件A");
                lblStatus.setForeground(new Color(0, 128, 0));
            }
        } catch (Exception e) { 
            if (!isHeadless) {
                lblStatus.setText("状态: 连接失败"); 
                lblStatus.setForeground(Color.RED);
            }
        }
    }

    private boolean verifyPasswordRemote(int drawerIndex, String plainPwd) {
        String enc = encryptPassword(plainPwd);
        if (enc == null) return false;
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {
            byte[] pwdData = enc.getBytes(StandardCharsets.UTF_8);
            byte[] req = modbusReqWithData((byte)0x65, drawerIndex + 1, pwdData);
            out.write(req);
            byte[] resp = readModbusResponse(in);
            return resp != null && resp.length >= 12 && (resp[10] & 0xFF) == 0xFF;
        } catch (Exception e) { return false; }
    }

    private void handleDrawerToggle(int index) {
        if (drawerLocked[index] && !btnDrawers[index].isSelected()) {
            String pwd = JOptionPane.showInputDialog(null, "请输入抽屉 " + (index + 1) + " 的密码：");
            if (pwd == null || !verifyPasswordRemote(index, pwd)) {
                JOptionPane.showMessageDialog(null, "密码错误");
                btnDrawers[index].setSelected(false);
                return;
            }
            writeDrawerStatus(index, true);
        } else {
            writeDrawerStatus(index, btnDrawers[index].isSelected());
        }
    }

    private void writeDrawerStatus(int index, boolean status) {
        try (Socket sock = new Socket("127.0.0.1", 5020);
             OutputStream out = sock.getOutputStream()) {
            out.write(modbusReq((byte)0x05, index + 1, status ? 0xFF00 : 0x0000));
            btnDrawers[index].setSelected(status);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "控制失败: " + e.getMessage());
        }
    }

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
            int days = Integer.parseInt(daysField.getText());
            if (days < 1 || days > 7) { JOptionPane.showMessageDialog(null, "有效期必须为1~7天"); return; }
            String encPwd = encryptPassword(pwd);
            if (encPwd == null) return;
            try (Socket sock = new Socket("127.0.0.1", 5020);
                 OutputStream out = sock.getOutputStream()) {
                byte[] pwdData = encPwd.getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[4 + pwdData.length];
                payload[0] = (byte)((index + 1) >> 8); payload[1] = (byte)((index + 1) & 0xFF);
                payload[2] = (byte)(days >> 8); payload[3] = (byte)(days & 0xFF);
                System.arraycopy(pwdData, 0, payload, 4, pwdData.length);
                byte[] req = modbusReqWithData((byte)0x64, index + 1, payload);
                out.write(req);
                JOptionPane.showMessageDialog(null, "密码设置成功");
            } catch (Exception ex) { JOptionPane.showMessageDialog(null, "设置失败: " + ex.getMessage()); }
        }
    }

    private void showSetDrawerTempDialog(int index) {
        String tempStr = JOptionPane.showInputDialog(null, "设置抽屉 " + (index + 1) + " 温度 (℃):");
        if (tempStr == null) return;
        double temp = Double.parseDouble(tempStr);
        if (drawerLocked[index]) {
            String pwd = JOptionPane.showInputDialog(null, "请输入抽屉密码：");
            if (pwd == null) return;
            String encPwd = encryptPassword(pwd);
            if (encPwd == null) return;
            try (Socket sock = new Socket("127.0.0.1", 5020);
                 OutputStream out = sock.getOutputStream();
                 InputStream in = sock.getInputStream()) {
                byte[] pwdData = encPwd.getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[4 + pwdData.length];
                int tempInt = (int)(temp * 10);
                payload[0] = (byte)((index + 1) >> 8); payload[1] = (byte)((index + 1) & 0xFF);
                payload[2] = (byte)(tempInt >> 8); payload[3] = (byte)(tempInt & 0xFF);
                System.arraycopy(pwdData, 0, payload, 4, pwdData.length);
                byte[] req = modbusReqWithData((byte)0x68, index + 1, payload);
                out.write(req);
                byte[] resp = readModbusResponse(in);
                if (resp != null && resp.length >= 12 && (resp[10] & 0xFF) == 0xFF) {
                    JOptionPane.showMessageDialog(null, "温度设置成功");
                } else {
                    JOptionPane.showMessageDialog(null, "设置失败：密码错误或通信异常");
                }
            } catch (Exception ex) { JOptionPane.showMessageDialog(null, "设置失败: " + ex.getMessage()); }
        } else {
            int tempInt = (int)(temp * 10);
            try (Socket sock = new Socket("127.0.0.1", 5020);
                 OutputStream out = sock.getOutputStream()) {
                out.write(modbusReq((byte)0x06, index + 1, tempInt));
                JOptionPane.showMessageDialog(null, "温度设置成功");
            } catch (Exception ex) { JOptionPane.showMessageDialog(null, "设置失败: " + ex.getMessage()); }
        }
    }
}
