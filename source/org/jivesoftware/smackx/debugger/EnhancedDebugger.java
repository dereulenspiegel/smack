/**
* $RCSfile$
* $Revision$
* $Date$
*
* Copyright (C) 2002-2003 Jive Software. All rights reserved.
* ====================================================================
* The Jive Software License (based on Apache Software License, Version 1.1)
*
* Redistribution and use in source and binary forms, with or without
* modification, are permitted provided that the following conditions
* are met:
*
* 1. Redistributions of source code must retain the above copyright
*    notice, this list of conditions and the following disclaimer.
*
* 2. Redistributions in binary form must reproduce the above copyright
*    notice, this list of conditions and the following disclaimer in
*    the documentation and/or other materials provided with the
*    distribution.
*
* 3. The end-user documentation included with the redistribution,
*    if any, must include the following acknowledgment:
*       "This product includes software developed by
*        Jive Software (http://www.jivesoftware.com)."
*    Alternately, this acknowledgment may appear in the software itself,
*    if and wherever such third-party acknowledgments normally appear.
*
* 4. The names "Smack" and "Jive Software" must not be used to
*    endorse or promote products derived from this software without
*    prior written permission. For written permission, please
*    contact webmaster@jivesoftware.com.
*
* 5. Products derived from this software may not be called "Smack",
*    nor may "Smack" appear in their name, without prior written
*    permission of Jive Software.
*
* THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
* WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
* OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
* DISCLAIMED.  IN NO EVENT SHALL JIVE SOFTWARE OR
* ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
* SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
* LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
* USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
* ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
* OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
* OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
* SUCH DAMAGE.
* ====================================================================
*/

package org.jivesoftware.smackx.debugger;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.text.*;
import java.util.Date;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import javax.xml.transform.*;
import javax.xml.transform.stream.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.debugger.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.*;

/**
 * The EnhancedDebugger is a debugger that allows to debug sent, received and interpreted messages 
 * but also provides the ability to send ad-hoc messages composed by the user.<p>
 * 
 * A new EnhancedDebugger will be created for each connection to debug. All the EnhancedDebuggers 
 * will be shown in the same debug window provided by the class EnhancedDebuggerWindow.
 * 
 * @author Gaston Dombiak
 */
public class EnhancedDebugger implements SmackDebugger {

    private static final String NEWLINE = "\n";

    private static ImageIcon packetReceivedIcon;
    private static ImageIcon packetSentIcon;
    private static ImageIcon presencePacketIcon;
    private static ImageIcon iqPacketIcon;
    private static ImageIcon messagePacketIcon;
    private static ImageIcon unknownPacketTypeIcon;

    {
        URL url;
        // Load the image icons 
        url =
            Thread.currentThread().getContextClassLoader().getResource("images/nav_left_blue.png");
        if (url != null) {
            packetReceivedIcon = new ImageIcon(url);
        }
        url =
            Thread.currentThread().getContextClassLoader().getResource("images/nav_right_red.png");
        if (url != null) {
            packetSentIcon = new ImageIcon(url);
        }
        url =
            Thread.currentThread().getContextClassLoader().getResource("images/photo_portrait.png");
        if (url != null) {
            presencePacketIcon = new ImageIcon(url);
        }
        url =
            Thread.currentThread().getContextClassLoader().getResource(
                "images/question_and_answer.png");
        if (url != null) {
            iqPacketIcon = new ImageIcon(url);
        }
        url = Thread.currentThread().getContextClassLoader().getResource("images/message.png");
        if (url != null) {
            messagePacketIcon = new ImageIcon(url);
        }
        url = Thread.currentThread().getContextClassLoader().getResource("images/unknown.png");
        if (url != null) {
            unknownPacketTypeIcon = new ImageIcon(url);
        }
    }

    private DefaultTableModel messagesTable = null;
    private JTextArea messageTextArea = null;
    private JFormattedTextField userField = null;
    private JFormattedTextField statusField = null;

    private XMPPConnection connection = null;

    private PacketListener packetReaderListener = null;
    private PacketListener packetWriterListener = null;
    private ConnectionListener connListener = null;

    private Writer writer;
    private Reader reader;
    private ReaderListener readerListener;
    private WriterListener writerListener;

    private Date creationTime = new Date();

    // Statistics variables
    private DefaultTableModel statisticsTable = null;
    private int sentPackets = 0;
    private int receivedPackets = 0;
    private int sentIQPackets = 0;
    private int receivedIQPackets = 0;
    private int sentMessagePackets = 0;
    private int receivedMessagePackets = 0;
    private int sentPresencePackets = 0;
    private int receivedPresencePackets = 0;
    private int sentOtherPackets = 0;
    private int receivedOtherPackets = 0;

    JTabbedPane tabbedPane;

    public EnhancedDebugger(XMPPConnection connection, Writer writer, Reader reader) {
        this.connection = connection;
        this.writer = writer;
        this.reader = reader;
        createDebug();
        EnhancedDebuggerWindow.addDebugger(this);
    }

    /**
     * Creates the debug process, which is a GUI window that displays XML traffic.
     */
    private void createDebug() {
        // We'll arrange the UI into six tabs. The first tab contains all data, the second
        // client generated XML, the third server generated XML, the fourth allows to send 
        // ad-hoc messages and the fifth contains connection information.
        tabbedPane = new JTabbedPane();

        // Add the All Packets, Sent, Received and Interpreted panels
        addBasicPanels();

        // Add the panel to send ad-hoc messages
        addAdhocPacketPanel();

        // Add the connection information panel
        addInformationPanel();

        // Create a thread that will listen for all incoming packets and write them to
        // the GUI. This is what we call "interpreted" packet data, since it's the packet
        // data as Smack sees it and not as it's coming in as raw XML.
        packetReaderListener = new PacketListener() {
            SimpleDateFormat dateFormatter = new SimpleDateFormat("hh:mm:ss aaa");
            public void processPacket(Packet packet) {
                addReadPacketToTable(dateFormatter, packet);
            }
        };

        // Create a thread that will listen for all outgoing packets and write them to
        // the GUI.
        packetWriterListener = new PacketListener() {
            SimpleDateFormat dateFormatter = new SimpleDateFormat("hh:mm:ss aaa");
            public void processPacket(Packet packet) {
                addSentPacketToTable(dateFormatter, packet);
            }
        };

        // Create a thread that will listen for any connection closed event
        connListener = new ConnectionListener() {
            public void connectionClosed() {
                statusField.setValue("Closed");
                EnhancedDebuggerWindow.connectionClosed(EnhancedDebugger.this);
            }

            public void connectionClosedOnError(Exception e) {
                statusField.setValue("Closed due to an exception");
                EnhancedDebuggerWindow.connectionClosedOnError(EnhancedDebugger.this, e);
            }
        };
    }

    private void addBasicPanels() {
        JPanel allPane = new JPanel();
        allPane.setLayout(new GridLayout(2, 1));
        tabbedPane.add("All Packets", allPane);
        tabbedPane.setToolTipTextAt(0, "Sent and received packets processed by Smack");

        messagesTable =
            new DefaultTableModel(
                new Object[] { "Hide", "Timestamp", "", "", "Message", "Id", "Type", "To", "From" },
                0) {
            public boolean isCellEditable(int rowIndex, int mColIndex) {
                return false;
            }
            public Class getColumnClass(int columnIndex) {
                if (columnIndex == 2 || columnIndex == 3) {
                    return Icon.class;
                }
                return super.getColumnClass(columnIndex);
            }

        };
        JTable table = new JTable(messagesTable);
        // Allow only single a selection
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Hide the first column 
        table.getColumnModel().getColumn(0).setMaxWidth(0);
        table.getColumnModel().getColumn(0).setMinWidth(0);
        table.getTableHeader().getColumnModel().getColumn(0).setMaxWidth(0);
        table.getTableHeader().getColumnModel().getColumn(0).setMinWidth(0);
        // Set the column "timestamp" size
        table.getColumnModel().getColumn(1).setMaxWidth(300);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        // Set the column "direction" icon size
        table.getColumnModel().getColumn(2).setMaxWidth(50);
        table.getColumnModel().getColumn(2).setPreferredWidth(30);
        // Set the column "packet type" icon size
        table.getColumnModel().getColumn(3).setMaxWidth(50);
        table.getColumnModel().getColumn(3).setPreferredWidth(30);
        // Set the column "Id" size
        table.getColumnModel().getColumn(5).setMaxWidth(100);
        table.getColumnModel().getColumn(5).setPreferredWidth(55);
        // Set the column "type" size
        table.getColumnModel().getColumn(6).setMaxWidth(200);
        table.getColumnModel().getColumn(6).setPreferredWidth(50);
        // Set the column "to" size
        table.getColumnModel().getColumn(7).setMaxWidth(300);
        table.getColumnModel().getColumn(7).setPreferredWidth(90);
        // Set the column "from" size
        table.getColumnModel().getColumn(8).setMaxWidth(300);
        table.getColumnModel().getColumn(8).setPreferredWidth(90);
        // Create a table listener that listen for row selection events
        SelectionListener selectionListener = new SelectionListener(table);
        table.getSelectionModel().addListSelectionListener(selectionListener);
        table.getColumnModel().getSelectionModel().addListSelectionListener(selectionListener);
        allPane.add(new JScrollPane(table));
        messageTextArea = new JTextArea();
        messageTextArea.setEditable(false);
        // Add pop-up menu.
        JPopupMenu menu = new JPopupMenu();
        JMenuItem menuItem1 = new JMenuItem("Copy");
        menuItem1.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // Get the clipboard
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                // Set the sent text as the new content of the clipboard
                clipboard.setContents(new StringSelection(messageTextArea.getText()), null);
            }
        });
        menu.add(menuItem1);
        // Add listener to the text area so the popup menu can come up.
        messageTextArea.addMouseListener(new PopupListener(menu));
        allPane.add(new JScrollPane(messageTextArea));

        // Create UI elements for client generated XML traffic.
        final JTextArea sentText = new JTextArea();
        sentText.setEditable(false);
        sentText.setForeground(new Color(112, 3, 3));
        tabbedPane.add("Raw Sent Packets", new JScrollPane(sentText));
        tabbedPane.setToolTipTextAt(1, "Raw text of the sent packets");

        // Add pop-up menu.
        menu = new JPopupMenu();
        menuItem1 = new JMenuItem("Copy");
        menuItem1.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // Get the clipboard
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                // Set the sent text as the new content of the clipboard
                clipboard.setContents(new StringSelection(sentText.getText()), null);
            }
        });

        JMenuItem menuItem2 = new JMenuItem("Clear");
        menuItem2.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                sentText.setText("");
            }
        });

        // Add listener to the text area so the popup menu can come up.
        sentText.addMouseListener(new PopupListener(menu));
        menu.add(menuItem1);
        menu.add(menuItem2);

        // Create UI elements for server generated XML traffic.
        final JTextArea receivedText = new JTextArea();
        receivedText.setEditable(false);
        receivedText.setForeground(new Color(6, 76, 133));
        tabbedPane.add("Raw Received Packets", new JScrollPane(receivedText));
        tabbedPane.setToolTipTextAt(
            2,
            "Raw text of the received packets before Smack process them");

        // Add pop-up menu.
        menu = new JPopupMenu();
        menuItem1 = new JMenuItem("Copy");
        menuItem1.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // Get the clipboard
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                // Set the sent text as the new content of the clipboard
                clipboard.setContents(new StringSelection(receivedText.getText()), null);
            }
        });

        menuItem2 = new JMenuItem("Clear");
        menuItem2.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                receivedText.setText("");
            }
        });

        // Add listener to the text area so the popup menu can come up.
        receivedText.addMouseListener(new PopupListener(menu));
        menu.add(menuItem1);
        menu.add(menuItem2);

        // Create a special Reader that wraps the main Reader and logs data to the GUI.
        ObservableReader debugReader = new ObservableReader(reader);
        readerListener = new ReaderListener() {
                    public void read(String str) {
                        int index = str.lastIndexOf(">");
                        if (index != -1) {
                            receivedText.append(str.substring(0, index + 1));
                            receivedText.append(NEWLINE);
                            if (str.length() > index) {
                                receivedText.append(str.substring(index + 1));
                            }
                        }
                        else {
                            receivedText.append(str);
                        }
                    }
                };
        debugReader.addReaderListener(readerListener);

        // Create a special Writer that wraps the main Writer and logs data to the GUI.
        ObservableWriter debugWriter = new ObservableWriter(writer);
        writerListener = new WriterListener() {
                    public void write(String str) {
                        sentText.append(str);
                        if (str.endsWith(">")) {
                            sentText.append(NEWLINE);
                        }
                    }
                };
        debugWriter.addWriterListener(writerListener);

        // Assign the reader/writer objects to use the debug versions. The packet reader
        // and writer will use the debug versions when they are created.
        reader = debugReader;
        writer = debugWriter;

    }

    private void addAdhocPacketPanel() {
        // Create UI elements for sending ad-hoc messages.
        final JTextArea adhocMessages = new JTextArea();
        adhocMessages.setEditable(true);
        adhocMessages.setForeground(new Color(1, 94, 35));
        tabbedPane.add("Ad-hoc message", new JScrollPane(adhocMessages));
        tabbedPane.setToolTipTextAt(3, "Panel that allows you to send adhoc packets");

        // Add pop-up menu.
        JPopupMenu menu = new JPopupMenu();
        JMenuItem menuItem = new JMenuItem("Message");
        menuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                adhocMessages.setText(
                    "<message to=\"\" id=\""
                        + StringUtils.randomString(5)
                        + "-X\"><body></body></message>");
            }
        });
        menu.add(menuItem);

        menuItem = new JMenuItem("IQ Get");
        menuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                adhocMessages.setText(
                    "<iq type=\"get\" to=\"\" id=\""
                        + StringUtils.randomString(5)
                        + "-X\"><query xmlns=\"\"></query></iq>");
            }
        });
        menu.add(menuItem);

        menuItem = new JMenuItem("IQ Set");
        menuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                adhocMessages.setText(
                    "<iq type=\"set\" to=\"\" id=\""
                        + StringUtils.randomString(5)
                        + "-X\"><query xmlns=\"\"></query></iq>");
            }
        });
        menu.add(menuItem);

        menuItem = new JMenuItem("Presence");
        menuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                adhocMessages.setText(
                    "<presence to=\"\" id=\"" + StringUtils.randomString(5) + "-X\"/>");
            }
        });
        menu.add(menuItem);
        menu.addSeparator();

        menuItem = new JMenuItem("Send");
        menuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!"".equals(adhocMessages.getText())) {
                    AdHocPacket packetToSend = new AdHocPacket(adhocMessages.getText());
                    connection.sendPacket(packetToSend);
                }
            }
        });
        menu.add(menuItem);

        menuItem = new JMenuItem("Clear");
        menuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                adhocMessages.setText(null);
            }
        });
        menu.add(menuItem);

        // Add listener to the text area so the popup menu can come up.
        adhocMessages.addMouseListener(new PopupListener(menu));
    }

    private void addInformationPanel() {
        // Create UI elements for connection information.
        JPanel informationPanel = new JPanel();
        informationPanel.setLayout(new BorderLayout());

        // Add the Host information
        JPanel connPanel = new JPanel();
        connPanel.setLayout(new GridBagLayout());
        connPanel.setBorder(BorderFactory.createTitledBorder("Connection information"));

        JLabel label = new JLabel("Host: ");
        label.setMinimumSize(new java.awt.Dimension(150, 14));
        label.setMaximumSize(new java.awt.Dimension(150, 14));
        connPanel.add(
            label,
            new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0, 21, 0, new Insets(0, 0, 0, 0), 0, 0));
        JFormattedTextField field = new JFormattedTextField(connection.getHost());
        field.setMinimumSize(new java.awt.Dimension(150, 20));
        field.setMaximumSize(new java.awt.Dimension(150, 20));
        field.setEditable(false);
        field.setBorder(null);
        connPanel.add(
            field,
            new GridBagConstraints(1, 0, 1, 1, 1.0, 0.0, 10, 2, new Insets(0, 0, 0, 0), 0, 0));

        // Add the Port information
        label = new JLabel("Port: ");
        label.setMinimumSize(new java.awt.Dimension(150, 14));
        label.setMaximumSize(new java.awt.Dimension(150, 14));
        connPanel.add(
            label,
            new GridBagConstraints(0, 1, 1, 1, 0.0, 0.0, 21, 0, new Insets(0, 0, 0, 0), 0, 0));
        field = new JFormattedTextField(new Integer(connection.getPort()));
        field.setMinimumSize(new java.awt.Dimension(150, 20));
        field.setMaximumSize(new java.awt.Dimension(150, 20));
        field.setEditable(false);
        field.setBorder(null);
        connPanel.add(
            field,
            new GridBagConstraints(1, 1, 1, 1, 0.0, 0.0, 10, 2, new Insets(0, 0, 0, 0), 0, 0));

        // Add the connection's User information
        label = new JLabel("User: ");
        label.setMinimumSize(new java.awt.Dimension(150, 14));
        label.setMaximumSize(new java.awt.Dimension(150, 14));
        connPanel.add(
            label,
            new GridBagConstraints(0, 2, 1, 1, 0.0, 0.0, 21, 0, new Insets(0, 0, 0, 0), 0, 0));
        userField = new JFormattedTextField();
        userField.setMinimumSize(new java.awt.Dimension(150, 20));
        userField.setMaximumSize(new java.awt.Dimension(150, 20));
        userField.setEditable(false);
        userField.setBorder(null);
        connPanel.add(
            userField,
            new GridBagConstraints(1, 2, 1, 1, 0.0, 0.0, 10, 2, new Insets(0, 0, 0, 0), 0, 0));

        // Add the connection's creationTime information
        label = new JLabel("Creation time: ");
        label.setMinimumSize(new java.awt.Dimension(150, 14));
        label.setMaximumSize(new java.awt.Dimension(150, 14));
        connPanel.add(
            label,
            new GridBagConstraints(0, 3, 1, 1, 0.0, 0.0, 21, 0, new Insets(0, 0, 0, 0), 0, 0));
        field = new JFormattedTextField(new SimpleDateFormat("yyyy.MM.dd hh:mm:ss aaa"));
        field.setMinimumSize(new java.awt.Dimension(150, 20));
        field.setMaximumSize(new java.awt.Dimension(150, 20));
        field.setValue(creationTime);
        field.setEditable(false);
        field.setBorder(null);
        connPanel.add(
            field,
            new GridBagConstraints(1, 3, 1, 1, 0.0, 0.0, 10, 2, new Insets(0, 0, 0, 0), 0, 0));

        // Add the connection's creationTime information
        label = new JLabel("Status: ");
        label.setMinimumSize(new java.awt.Dimension(150, 14));
        label.setMaximumSize(new java.awt.Dimension(150, 14));
        connPanel.add(
            label,
            new GridBagConstraints(0, 4, 1, 1, 0.0, 0.0, 21, 0, new Insets(0, 0, 0, 0), 0, 0));
        statusField = new JFormattedTextField();
        statusField.setMinimumSize(new java.awt.Dimension(150, 20));
        statusField.setMaximumSize(new java.awt.Dimension(150, 20));
        statusField.setValue("Active");
        statusField.setEditable(false);
        statusField.setBorder(null);
        connPanel.add(
            statusField,
            new GridBagConstraints(1, 4, 1, 1, 0.0, 0.0, 10, 2, new Insets(0, 0, 0, 0), 0, 0));
        // Add the connection panel to the information panel
        informationPanel.add(connPanel, BorderLayout.NORTH);

        // Add the Number of sent packets information
        JPanel packetsPanel = new JPanel();
        packetsPanel.setLayout(new GridLayout(1, 1));
        packetsPanel.setBorder(BorderFactory.createTitledBorder("Transmitted Packets"));

        statisticsTable =
            new DefaultTableModel(new Object[][] { { "IQ", new Integer(0), new Integer(0)}, {
                "Message", new Integer(0), new Integer(0)
                }, {
                "Presence", new Integer(0), new Integer(0)
                }, {
                "Other", new Integer(0), new Integer(0)
                }, {
                "Total", new Integer(0), new Integer(0)
                }
        }, new Object[] { "Type", "Received", "Sent" }) {
            public boolean isCellEditable(int rowIndex, int mColIndex) {
                return false;
            }
        };
        JTable table = new JTable(statisticsTable);
        // Allow only single a selection
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        packetsPanel.add(new JScrollPane(table));

        // Add the packets panel to the information panel
        informationPanel.add(packetsPanel, BorderLayout.CENTER);

        tabbedPane.add("Information", new JScrollPane(informationPanel));
        tabbedPane.setToolTipTextAt(4, "Information and statistics about the debugged connection");
    }

    public void userHasLogged(String user) {
        userField.setText(user);
        EnhancedDebuggerWindow.userHasLogged(this, user);
        // Add the connection listener to the connection so that the debugger can be notified
        // whenever the connection is closed.
        connection.addConnectionListener(connListener);
    }

    public Reader getReader() {
        return reader;
    }

    public Writer getWriter() {
        return writer;
    }

    public PacketListener getReaderListener() {
        return packetReaderListener;
    }

    public PacketListener getWriterListener() {
        return packetWriterListener;
    }

    /**
     * Updates the statistics table
     */
    private void updateStatistics() {
        statisticsTable.setValueAt(new Integer(receivedIQPackets), 0, 1);
        statisticsTable.setValueAt(new Integer(sentIQPackets), 0, 2);

        statisticsTable.setValueAt(new Integer(receivedMessagePackets), 1, 1);
        statisticsTable.setValueAt(new Integer(sentMessagePackets), 1, 2);

        statisticsTable.setValueAt(new Integer(receivedPresencePackets), 2, 1);
        statisticsTable.setValueAt(new Integer(sentPresencePackets), 2, 2);

        statisticsTable.setValueAt(new Integer(receivedOtherPackets), 3, 1);
        statisticsTable.setValueAt(new Integer(sentOtherPackets), 3, 2);

        statisticsTable.setValueAt(new Integer(receivedPackets), 4, 1);
        statisticsTable.setValueAt(new Integer(sentPackets), 4, 2);
    }

    /**
     * Adds the received packet detail to the messages table.
     * 
     * @param dateFormatter the SimpleDateFormat to use to format Dates
     * @param packet the read packet to add to the table
     */
    private void addReadPacketToTable(SimpleDateFormat dateFormatter, Packet packet) {
        String messageType = null;
        String from = packet.getFrom();
        String type = "";
        Icon packetTypeIcon;
        receivedPackets++;
        if (packet instanceof IQ) {
            packetTypeIcon = iqPacketIcon;
            messageType = "IQ Received (class=" + packet.getClass().getName() + ")";
            type = ((IQ) packet).getType().toString();
            receivedIQPackets++;
        }
        else if (packet instanceof Message) {
            packetTypeIcon = messagePacketIcon;
            messageType = "Message Received";
            type = ((Message) packet).getType().toString();
            receivedMessagePackets++;
        }
        else if (packet instanceof Presence) {
            packetTypeIcon = presencePacketIcon;
            messageType = "Presence Received";
            type = ((Presence) packet).getType().toString();
            receivedPresencePackets++;
        }
        else {
            packetTypeIcon = unknownPacketTypeIcon;
            messageType = packet.getClass().getName() + " Received";
            receivedOtherPackets++;
        }

        messagesTable.addRow(
            new Object[] {
                formatXML(packet.toXML()),
                dateFormatter.format(new Date()),
                packetReceivedIcon,
                packetTypeIcon,
                messageType,
                packet.getPacketID(),
                type,
                "",
                from });
        // Update the statistics table
        updateStatistics();
    }

    /**
     * Adds the sent packet detail to the messages table.
     * 
     * @param dateFormatter the SimpleDateFormat to use to format Dates
     * @param packet the sent packet to add to the table
     */
    private void addSentPacketToTable(SimpleDateFormat dateFormatter, Packet packet) {
        String messageType = null;
        String to = packet.getTo();
        String type = "";
        Icon packetTypeIcon;
        sentPackets++;
        if (packet instanceof IQ) {
            packetTypeIcon = iqPacketIcon;
            messageType = "IQ Sent (class=" + packet.getClass().getName() + ")";
            type = ((IQ) packet).getType().toString();
            sentIQPackets++;
        }
        else if (packet instanceof Message) {
            packetTypeIcon = messagePacketIcon;
            messageType = "Message Sent";
            type = ((Message) packet).getType().toString();
            sentMessagePackets++;
        }
        else if (packet instanceof Presence) {
            packetTypeIcon = presencePacketIcon;
            messageType = "Presence Sent";
            type = ((Presence) packet).getType().toString();
            sentPresencePackets++;
        }
        else {
            packetTypeIcon = unknownPacketTypeIcon;
            messageType = packet.getClass().getName() + " Sent";
            sentOtherPackets++;
        }

        messagesTable.addRow(
            new Object[] {
                formatXML(packet.toXML()),
                dateFormatter.format(new Date()),
                packetSentIcon,
                packetTypeIcon,
                messageType,
                packet.getPacketID(),
                type,
                to,
                "" });

        // Update the statistics table
        updateStatistics();
    }

    private String formatXML(String str) {
        try {
            // Use a Transformer for output
            TransformerFactory tFactory = TransformerFactory.newInstance();
            Transformer transformer = tFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            // Transform the requested string into a nice formatted XML string
            StreamSource source = new StreamSource(new StringReader(str));
            StringWriter sw = new StringWriter();
            StreamResult result = new StreamResult(sw);
            transformer.transform(source, result);
            return sw.toString();

        }
        catch (TransformerConfigurationException tce) {
            // Error generated by the parser
            System.out.println("\n** Transformer Factory error");
            System.out.println("   " + tce.getMessage());

            // Use the contained exception, if any
            Throwable x = tce;
            if (tce.getException() != null)
                x = tce.getException();
            x.printStackTrace();

        }
        catch (TransformerException te) {
            // Error generated by the parser
            System.out.println("\n** Transformation error");
            System.out.println("   " + te.getMessage());

            // Use the contained exception, if any
            Throwable x = te;
            if (te.getException() != null)
                x = te.getException();
            x.printStackTrace();

        }
        return str;
    }
    
    /**
     * Returns true if the debugger's connection with the server is up and running.
     *
     * @return true if the connection with the server is active.
     */
    boolean isConnectionActive() {
        return connection.isConnected();
    }

    /**
     * Stops debugging the connection. Removes any listener on the connection.
     *
     */
    void cancel() {
        connection.removeConnectionListener(connListener);
        connection.removePacketListener(packetReaderListener);
        connection.removePacketWriterListener(packetWriterListener);
        ((ObservableReader)reader).removeReaderListener(readerListener);
        ((ObservableWriter)writer).removeWriterListener(writerListener);
        messagesTable = null;
    }

    /**
     * An ad-hoc packet is like any regular packet but with the exception that it's intention is 
     * to be used only <b>to send packets</b>.<p>
     * 
     * The whole text to send must be passed to the constructor. This implies that the client of 
     * this class is responsible for sending a valid text to the constructor. 
     * 
     */
    private class AdHocPacket extends Packet {

        private String text;

        /**
         * Create a new AdHocPacket with the text to send. The passed text must be a valid text to 
         * send to the server, no validation will be done on the passed text.
         * 
         * @param text the whole text of the packet to send 
         */
        public AdHocPacket(String text) {
            this.text = text;
        }

        public String toXML() {
            return text;
        }

    }

    /**
     * Listens for debug window popup dialog events.
     */
    private class PopupListener extends MouseAdapter {
        JPopupMenu popup;

        PopupListener(JPopupMenu popupMenu) {
            popup = popupMenu;
        }

        public void mousePressed(MouseEvent e) {
            maybeShowPopup(e);
        }

        public void mouseReleased(MouseEvent e) {
            maybeShowPopup(e);
        }

        private void maybeShowPopup(MouseEvent e) {
            if (e.isPopupTrigger()) {
                popup.show(e.getComponent(), e.getX(), e.getY());
            }
        }
    }

    private class SelectionListener implements ListSelectionListener {
        JTable table;

        // It is necessary to keep the table since it is not possible
        // to determine the table from the event's source
        SelectionListener(JTable table) {
            this.table = table;
        }
        public void valueChanged(ListSelectionEvent e) {
            if (table.getSelectedRow() == -1) {
                // Clear the messageTextArea since there is none packet selected 
                messageTextArea.setText(null);
            }
            else {
                // Set the detail of the packet in the messageTextArea 
                messageTextArea.setText(
                    (String) table.getModel().getValueAt(table.getSelectedRow(), 0));
                // Scroll up to the top
                messageTextArea.setCaretPosition(0);
            }
        }
    }
}
