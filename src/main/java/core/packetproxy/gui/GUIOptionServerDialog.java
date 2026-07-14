/*
 * Copyright 2019 DeNA Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package packetproxy.gui;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.regex.*;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import packetproxy.EncoderManager;
import packetproxy.common.I18nString;
import packetproxy.model.Server;

public class GUIOptionServerDialog extends JDialog {

	private static final long serialVersionUID = 1L;
	private JButton button_cancel = new JButton(I18nString.get("Cancel"));
	private JButton button_set = new JButton(I18nString.get("Save"));
	private HintTextField text_ip = new HintTextField("(ex.) aaa.bbb.ccc.com or 1.2.3.4");
	private HintTextField text_port = new HintTextField("(ex.) 80");
	private HintTextField text_comment = new HintTextField("(ex.) game server for test");
	private JCheckBox checkbox_ssl = new JCheckBox(I18nString.get("Need a SSL/TLS to connect"));
	private JCheckBox checkbox_dns = new JCheckBox(I18nString.get("Spoofing A Record"));
	private JCheckBox checkbox_dns6 = new JCheckBox(I18nString.get("Spoofing AAAA Record"));
	private JLabel label_dnsspoof = new JLabel(
			"Private DNS server needs to resolve the server name to local machine IP.");
	private static final String[] PROXY_TYPE_LABELS = {"Direct (no upstream proxy)", "HTTP proxy", "SOCKS5 proxy"};
	private JComboBox<String> combo_proxy_type = new JComboBox<String>();
	private HintTextField text_socks_user = new HintTextField("(ex.) proxyuser");
	private JPasswordField text_socks_password = new JPasswordField();
	private JPanel panelSocksAuth;
	JComboBox<String> combo = new JComboBox<String>();
	private JButton button_import_proto = new JButton(I18nString.get("Import Proto File"));
	private JPanel panelDescriptorPath;

	/** Working gRPC descriptor path; applied to [Server] on Save. */
	private String grpcDescriptorPath;

	private Integer editingServerId;
	private int height = 660;
	private int width = 700;
	private Server server = null;

	private JComponent label_and_object(String label_name, JComponent object) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
		JLabel label = new JLabel(label_name);
		label.setPreferredSize(new Dimension(150, label.getMaximumSize().height));
		panel.add(label);
		object.setMaximumSize(new Dimension(Short.MAX_VALUE, label.getMaximumSize().height * 2));
		panel.add(object);
		return panel;
	}

	private JComponent buttons() {
		JPanel panel_button = new JPanel();
		panel_button.setLayout(new BoxLayout(panel_button, BoxLayout.X_AXIS));
		panel_button.setMaximumSize(new Dimension(Short.MAX_VALUE, button_set.getMaximumSize().height));
		panel_button.add(button_cancel);
		panel_button.add(button_set);
		return panel_button;
	}

	public Server showDialog(Server preset) {
		editingServerId = preset.getId();
		text_ip.setText(preset.getIp());
		text_port.setText(Integer.toString(preset.getPort()));
		combo.setSelectedItem(preset.getEncoder());
		checkbox_ssl.setSelected(preset.getUseSSL());
		setSelectedProxyType(preset.getProxyType());
		text_socks_user.setText(preset.getSocksUser() != null ? preset.getSocksUser() : "");
		text_socks_password.setText(preset.getSocksPassword() != null ? preset.getSocksPassword() : "");
		checkbox_dns.setSelected(preset.isResolved());
		checkbox_dns6.setSelected(preset.isResolved6());
		text_comment.setText(preset.getComment());
		String dp = preset.getDescriptorPath();
		grpcDescriptorPath = (dp != null && !dp.isEmpty()) ? dp : null;
		updateGrpcDescriptorUiVisibility();
		setModal(true);
		setVisible(true);
		if (server != null) {

			preset.setIp(text_ip.getText());
			preset.setPort(Integer.parseInt(text_port.getText()));
			preset.setEncoder(combo.getSelectedItem().toString());
			preset.setUseSSL(checkbox_ssl.isSelected());
			preset.setResolved(checkbox_dns.isSelected());
			preset.setResolved6(checkbox_dns6.isSelected());
			preset.setProxyType(selectedProxyType());
			preset.setSocksUser(emptyToNull(text_socks_user.getText()));
			preset.setSocksPassword(emptyToNull(new String(text_socks_password.getPassword())));
			preset.setComment(text_comment.getText());
			String path = grpcDescriptorPath != null ? grpcDescriptorPath.trim() : "";
			preset.setDescriptorPath(path.isEmpty() ? null : path);
			return preset;
		}
		return server;
	}

	public Server showDialog() {
		editingServerId = null;
		grpcDescriptorPath = null;
		updateGrpcDescriptorUiVisibility();
		EventQueue.invokeLater(new Runnable() {

			@Override
			public void run() {
				button_cancel.requestFocusInWindow();
			}
		});
		setModal(true);
		setVisible(true);
		return server;
	}

	private JComponent createModuleAlert() throws Exception {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
		JLabel label = new JLabel("<html>名前が重複したEncodeモジュールがあります。<br/>" + "重複したEncodeモジュールの名前は<br/>"
				+ "{モジュール名}-{モジュールのJarファイル名}として扱われます。</html>");
		label.setForeground(Color.red);
		label.setPreferredSize(new Dimension(150, label.getMaximumSize().height));
		panel.add(label);
		return panel;
	}

	private JComponent createModuleSetting() throws Exception {
		String[] names = EncoderManager.getInstance().getEncoderNameList();
		for (int i = 0; i < names.length; i++) {

			combo.addItem(names[i]);
		}
		combo.setEnabled(true);
		combo.setMaximumRowCount(names.length);
		combo.setSelectedItem("HTTP");
		return label_and_object(I18nString.get("Encode module:"), combo);
	}

	private JComponent createIpSetting() {
		return label_and_object(I18nString.get("Server name:"), text_ip);
	}

	private JComponent createPortSetting() {
		return label_and_object(I18nString.get("Server port:"), text_port);
	}

	private JComponent createUseSSLSetting() {
		return label_and_object(I18nString.get("Use SSL/TLS:"), checkbox_ssl);
	}

	private JComponent createProxyTypeSetting() {
		return label_and_object(I18nString.get("Upstream Proxy:"), combo_proxy_type);
	}

	private JComponent createSocksAuthSetting() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(label_and_object(I18nString.get("SOCKS Username:"), text_socks_user));
		panel.add(label_and_object(I18nString.get("SOCKS Password:"), text_socks_password));
		panelSocksAuth = panel;
		return panel;
	}

	private Server.ProxyType selectedProxyType() {
		switch (combo_proxy_type.getSelectedIndex()) {
			case 1 :
				return Server.ProxyType.HTTP;
			case 2 :
				return Server.ProxyType.SOCKS5;
			default :
				return Server.ProxyType.NONE;
		}
	}

	private void setSelectedProxyType(Server.ProxyType type) {
		switch (type) {
			case HTTP :
				combo_proxy_type.setSelectedIndex(1);
				break;
			case SOCKS5 :
				combo_proxy_type.setSelectedIndex(2);
				break;
			default :
				combo_proxy_type.setSelectedIndex(0);
				break;
		}
	}

	private void updateProxyDependentUi() {
		Server.ProxyType type = selectedProxyType();
		if (type != Server.ProxyType.NONE) {

			combo.setSelectedItem("HTTP");
			combo.setEnabled(false);
			checkbox_ssl.setSelected(false);
			checkbox_ssl.setEnabled(false);
			checkbox_dns.setSelected(false);
			checkbox_dns.setEnabled(false);
			checkbox_dns6.setSelected(false);
			checkbox_dns6.setEnabled(false);
		} else {

			combo.setEnabled(true);
			checkbox_ssl.setEnabled(true);
			checkbox_dns.setEnabled(true);
			checkbox_dns6.setEnabled(true);
		}
		if (panelSocksAuth != null) {

			panelSocksAuth.setVisible(type == Server.ProxyType.SOCKS5);
		}
		updateGrpcDescriptorUiVisibility();
	}

	private static String emptyToNull(String s) {
		if (s == null) {

			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private JComponent createDNSSettinglabel() {
		return label_and_object(I18nString.get("DNS Spoofing:"), label_dnsspoof);
	}

	private JComponent createDNSSetting() {
		return label_and_object(I18nString.get(" "), checkbox_dns);
	}

	private JComponent createDNS6Setting() {
		return label_and_object(I18nString.get(" "), checkbox_dns6);
	}

	private JComponent createCommentSetting() {
		return label_and_object(I18nString.get("Comments:"), text_comment);
	}

	private JComponent createDescriptorPathSetting() {
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		JLabel label = new JLabel(I18nString.get("gRPC descriptor (.desc):"));
		label.setPreferredSize(new Dimension(150, label.getMaximumSize().height));
		row.add(label);
		row.add(button_import_proto);
		row.add(Box.createHorizontalGlue());
		panelDescriptorPath = row;
		return row;
	}

	private void updateGrpcDescriptorUiVisibility() {
		boolean show = selectedProxyType() == Server.ProxyType.NONE;
		Object enc = combo.getSelectedItem();
		show = show && enc != null && ("gRPC".equals(enc.toString()) || "gRPC Streaming".equals(enc.toString()));
		if (panelDescriptorPath != null) {
			panelDescriptorPath.setVisible(show);
		}
	}

	public GUIOptionServerDialog(JFrame owner) throws Exception {
		super(owner);
		setTitle(I18nString.get("Server setting"));
		Rectangle rect = owner.getBounds();
		setBounds(rect.x + rect.width / 2 - width / 2, rect.y + rect.height / 2 - height / 2, width, height); /* ド真ん中 */

		for (String label : PROXY_TYPE_LABELS) {

			combo_proxy_type.addItem(I18nString.get(label));
		}
		combo_proxy_type.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				updateProxyDependentUi();
			}
		});

		combo.addActionListener(e -> updateGrpcDescriptorUiVisibility());

		button_import_proto.addActionListener(e -> {
			try {
				GUIOptionGrpcDescriptorDialog dlg = new GUIOptionGrpcDescriptorDialog((JFrame) getOwner(),
						editingServerId, grpcDescriptorPath);
				GrpcDescriptorDialogOutcome r = dlg.showManageDialog();
				if (r.isApplied()) {
					grpcDescriptorPath = r.getDescriptorPath();
				}
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(this, ex.getMessage(), I18nString.get("Error"),
						JOptionPane.ERROR_MESSAGE);
			}
		});

		Container c = getContentPane();
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

		panel.add(createIpSetting());
		panel.add(createPortSetting());
		panel.add(createUseSSLSetting());
		if (EncoderManager.getInstance().hasDuplicateModules()) {

			panel.add(createModuleAlert());
		}
		panel.add(createModuleSetting());
		panel.add(createDescriptorPathSetting());
		panel.add(createDNSSettinglabel());
		panel.add(createDNSSetting());
		panel.add(createDNS6Setting());
		panel.add(createProxyTypeSetting());
		panel.add(createSocksAuthSetting());
		panel.add(createCommentSetting());

		panel.add(buttons());

		c.add(panel);
		updateProxyDependentUi();

		button_cancel.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				server = null;
				dispose();
			}
		});

		button_set.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				String hostname = text_ip.getText();
				String regex = "[^\\x21-\\x7E]";
				Pattern p = Pattern.compile(regex);
				Matcher m = p.matcher(hostname);
				if (m.find()) {

					JOptionPane.showMessageDialog(null, I18nString.get("The ServerName contains invalid characters."));
					return;
				}
				server = new Server(text_ip.getText(), Integer.parseInt(text_port.getText()), checkbox_ssl.isSelected(),
						combo.getSelectedItem().toString(), checkbox_dns.isSelected(), checkbox_dns6.isSelected(),
						selectedProxyType() == Server.ProxyType.HTTP, text_comment.getText());
				server.setProxyType(selectedProxyType());
				server.setSocksUser(emptyToNull(text_socks_user.getText()));
				server.setSocksPassword(emptyToNull(new String(text_socks_password.getPassword())));
				String path = grpcDescriptorPath != null ? grpcDescriptorPath.trim() : "";
				server.setDescriptorPath(path.isEmpty() ? null : path);
				dispose();
			}
		});
	}
}
