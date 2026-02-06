import javax.swing.*;
import java.awt.*;

public class clientGUI extends JFrame {
    // Making these public so the main 'client' class can talk to them easily
    public JTextField ipF, portF, xF, yF, msgF;
    public JComboBox<String> colorBox;
    public JTextArea log;
    public JButton connBtn, postBtn, pinBtn, unpinBtn, shakeBtn, clearBtn, searchBtn;

    public clientGUI() {
        setTitle("CP372 Project Client");
        setSize(750, 500);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Top Section: Connection
        JPanel top = new JPanel();
        ipF = new JTextField("127.0.0.1", 8);
        portF = new JTextField("12345", 4);
        connBtn = new JButton("Connect");
        top.add(new JLabel("IP:")); top.add(ipF);
        top.add(new JLabel("Port:")); top.add(portF); top.add(connBtn);

        // Side Section: Big Action Buttons
        JPanel west = new JPanel(new GridLayout(4, 1, 5, 5));
        west.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        shakeBtn = new JButton("Shake");
        clearBtn = new JButton("Clear Board");
        pinBtn = new JButton("Pin (at X,Y)");
        unpinBtn = new JButton("Unpin (at X,Y)");
        west.add(shakeBtn); west.add(clearBtn); west.add(pinBtn); west.add(unpinBtn);

        // Bottom Section: Note Posting & Searching
        JPanel south = new JPanel();
        xF = new JTextField(3); yF = new JTextField(3);
        colorBox = new JComboBox<>();
        msgF = new JTextField(12);
        postBtn = new JButton("Post Note");
        searchBtn = new JButton("Search Message");
        south.add(new JLabel("X/Y:")); south.add(xF); south.add(yF);
        south.add(new JLabel("Color:")); south.add(colorBox);
        south.add(new JLabel("Text:")); south.add(msgF);
        south.add(postBtn); south.add(searchBtn);

        // Center Section: The Server Feed
        log = new JTextArea();
        log.setEditable(false);
        log.setFont(new Font("Consolas", Font.PLAIN, 12));
        
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(log), BorderLayout.CENTER);
        add(west, BorderLayout.WEST);
        add(south, BorderLayout.SOUTH);
    }
}