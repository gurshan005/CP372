import javax.swing.SwingUtilities;

public class client {
    private static clientGUI gui;
    private static clientLogic logic;

    public static void main(String[] args) {
        gui = new clientGUI();
        logic = new clientLogic();

        // 1. Hook up the Connect Button
        gui.connBtn.addActionListener(e -> {
            try {
                logic.connect(gui.ipF.getText(), Integer.parseInt(gui.portF.getText()), response -> {
                    // Logic to handle the "Handshake" for colors
                    if (response.startsWith("COLORS")) {
                        String[] colors = response.substring(7).split(" ");
                        SwingUtilities.invokeLater(() -> {
                            gui.colorBox.removeAllItems();
                            for (String c : colors) gui.colorBox.addItem(c);
                        });
                    }
                    // Update the text log with whatever the server says
                    gui.log.append("SERVER > " + response + "\n");
                });
                gui.log.append("Connecting to " + gui.ipF.getText() + "...\n");
            } catch (Exception ex) {
                gui.log.append("Error: " + ex.getMessage() + "\n");
            }
        });

        // 2. Hook up the Command Buttons
        gui.postBtn.addActionListener(e -> logic.send("POST " + gui.xF.getText() + " " + gui.yF.getText() + " " + gui.colorBox.getSelectedItem() + " " + gui.msgF.getText()));
        gui.pinBtn.addActionListener(e -> logic.send("PIN " + gui.xF.getText() + " " + gui.yF.getText()));
        gui.unpinBtn.addActionListener(e -> logic.send("UNPIN " + gui.xF.getText() + " " + gui.yF.getText()));
        gui.shakeBtn.addActionListener(e -> logic.send("SHAKE"));
        gui.clearBtn.addActionListener(e -> logic.send("CLEAR"));
        gui.searchBtn.addActionListener(e -> logic.send("GET refersTo=" + gui.msgF.getText()));

        // Show the window
        gui.setVisible(true);
    }
}