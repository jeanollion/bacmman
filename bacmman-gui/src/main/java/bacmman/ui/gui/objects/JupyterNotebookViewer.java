package bacmman.ui.gui.objects;

import bacmman.data_structure.dao.UUID;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EventObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static bacmman.ui.gui.MarkdownUtil.convertMarkdownToHtml;
import static bacmman.ui.gui.objects.JupyterNotebookViewer.CELL_TYPE.CODE;
import static bacmman.ui.gui.objects.JupyterNotebookViewer.CELL_TYPE.MARKDOWN;
import static bacmman.utils.Utils.loadIcon;

public class JupyterNotebookViewer extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(JupyterNotebookViewer.class);
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private JTree tree;
    private String filePath = "/data/PyBACMMAN_Basic_Selections.ipynb";
    JSONObject notebook;
    private String language = "python";
    private static String[] supportedLanguages = new String[]{"python", "java"};
    private static ImageIcon codeIcon = loadIcon(JupyterNotebookViewer.class, "/icons/code24.png");
    private static ImageIcon textIcon = loadIcon(JupyterNotebookViewer.class, "/icons/text24.png");
    private static ImageIcon notebookIcon = loadIcon(JupyterNotebookViewer.class, "/icons/notebook32.png");
    private RSyntaxTextArea description;

    public JupyterNotebookViewer() {
        setTitle("Jupyter Notebook Viewer");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        rootNode = new DefaultMutableTreeNode("Notebook");
        treeModel = new DefaultTreeModel(rootNode);
        tree = new JTree(treeModel);

        tree.setCellRenderer(new NotebookTreeCellRenderer());
        tree.setCellEditor(new NotebookTreeCellEditor(tree));
        tree.setEditable(true);
        tree.addMouseListener(new NotebookMouseAdapter());

        JScrollPane mainJSP = new JScrollPane(tree);
        add(mainJSP, BorderLayout.CENTER);

        loadNotebook(filePath);
    }

    private void loadNotebook(String filePath) {
        try {
            Path p = Paths.get(filePath);
            rootNode.setUserObject(Utils.removeExtension(p.getFileName().toString()));
            String content = new String(Files.readAllBytes(p));
            notebook = (JSONObject) new JSONParser().parse(content);
            JSONArray cells = (JSONArray) notebook.get("cells");
            JSONObject metadata = (JSONObject)notebook.getOrDefault("metadata", new JSONObject());
            JSONObject languageMetadata = (JSONObject)metadata.getOrDefault("language_info", new JSONObject());
            language = (String)languageMetadata.getOrDefault("name", "python");
            if (Stream.of(supportedLanguages).noneMatch(l -> l.equals(language))) language = "plain";

            rootNode.removeAllChildren();
            if (description != null) {
                RTextScrollPane descJSP = new RTextScrollPane(description);
                rootNode.add(new DefaultMutableTreeNode(descJSP));
            }
            for (Object cellObj : cells) {
                JSONObject cell = (JSONObject) cellObj;
                CellNode cellNode = new CellNode(cell, language);
                rootNode.add(cellNode);
            }
            treeModel.reload();
            Utils.expandAll(tree);
        } catch (IOException | ParseException e) {
            logger.debug("error loading notebook", e);
        }
    }

    enum CELL_TYPE {CODE, MARKDOWN}
    public class CellNode extends DefaultMutableTreeNode {
        JSONObject data;
        CELL_TYPE type;
        RSyntaxTextArea code;
        JEditorPane htmlPane;
        final String language;
        boolean markdownEdit = true;

        public CellNode(JSONObject data, String codeLanguage) {
            super("");
            this.type = CELL_TYPE.valueOf(((String)data.get("cell_type")).toUpperCase());
            this.data = data;
            this.language = codeLanguage;
            createCodeNode(getCodeString());
            createOutputNodes();
        }

        // constructor for empty cell
        public CellNode(CELL_TYPE type, String language) {
            super("");
            this.type = type;
            this.language = language;
            this.data = new JSONObject();
            data.put("cell_type", "code");
            data.put("source", new JSONArray());
            data.put("execution_count", null);
            data.put("outputs", new JSONArray());
            data.put("id", UUID.get().toHexString());
            data.put("metadata", createEmptyMetadata());
            createCodeNode("\t\t\t\t\t\t\t\t\n\n");
            createOutputNodes();
        }

        public JSONObject getData() { // update code and return data
            JSONArray source = (JSONArray)data.get("source");
            source.clear();
            for (String line : code.getText().split("\n")) source.add(line);
            return data;
        }

        public boolean setType(CELL_TYPE type) {
            if (type.equals(this.type)) return false;
            switch (type) {
                case CODE:
                default: {
                    setMarkdownEdit(true);
                    this.type = type;
                    code.setSyntaxEditingStyle("text/"+language);
                    data.put("metadata", createEmptyMetadata());
                    return true;
                }
                case MARKDOWN: {
                    this.type = type;
                    code.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
                    data.put("metadata", new JSONObject());
                    data.put("outputs", new JSONArray());
                    if (getChildCount()>1) { // remove output nodes
                        for (int i = getChildCount()-1; i>0; --i) remove(i);
                    }
                    setMarkdownEdit(false);
                    return true;
                }
            }
        }

        protected JSONObject createEmptyMetadata() {
            JSONObject metadata = new JSONObject();
            metadata.put("editable", true);
            metadata.put("tags", new JSONArray());
            JSONObject slideshow = new JSONObject();
            metadata.put("slideshow", slideshow);
            slideshow.put("slide_type", "");
            return metadata;
        }

        protected void createCodeNode(String initTxt) {
            htmlPane = new JEditorPane();
            htmlPane.setContentType("text/html");
            htmlPane.setEditable(false);
            htmlPane.setBorder(null);

            code = new RSyntaxTextArea();
            //code.setBorder(BorderFactory.createLineBorder(Color.BLACK));
            code.setEnabled(true);

            code.setLineWrap(false);
            code.setWrapStyleWord(true);
            code.setText(initTxt);
            if (CODE.equals(type)) {
                code.setSyntaxEditingStyle("text/"+language);
            } else if (MARKDOWN.equals(type)) {
                code.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
            }
            RTextScrollPane codeJSP = new RTextScrollPane(code);
            codeJSP.setLineNumbersEnabled(true);
            add(new DefaultMutableTreeNode(codeJSP));
            setMarkdownEdit(false);
        }

        protected void createOutputNodes() {
            if (CODE.equals(type)) {
                JSONArray outputs = (JSONArray) data.get("outputs");
                if (outputs != null) {
                    for (Object outputObj : outputs) {
                        JSONObject output = (JSONObject) outputObj;
                        String outputType = (String) output.get("output_type");
                        JSONObject data = (JSONObject) output.get("data");
                        if (data != null) {
                            JSONArray text = null;
                            if (data.containsKey("text/html")) {
                                text = (JSONArray) data.get("text/html");
                            } else if (data.containsKey("text/plain")) {
                                text = (JSONArray) data.get("text/plain");
                            }
                            if (text != null) {
                                add(new DefaultMutableTreeNode(new JLabel("<html>" + String.join("", text) + "</html>")));
                            }
                            if (data.containsKey("image/png")) {
                                ImageIcon imageIcon = createImageIcon((String) data.get("image/png"));
                                if (imageIcon != null) {
                                    add(new DefaultMutableTreeNode(new JLabel(imageIcon)));
                                }
                            }
                        }
                    }
                }
            }
        }

        public String getCodeString() {
            JSONArray source = (JSONArray) data.get("source");
            StringBuilder sourceS = new StringBuilder();
            for (Object line : source) {
                sourceS.append(line).append("\n");
            }
            return sourceS.toString();
        }

        public DefaultMutableTreeNode setMarkdownEdit(boolean edit) {
            if (this.type.equals(MARKDOWN) && edit != markdownEdit) {
                this.markdownEdit = edit;
                if (edit) {
                    RTextScrollPane codeJSP = new RTextScrollPane(code);
                    codeJSP.setLineNumbersEnabled(true);
                    remove(0);
                    DefaultMutableTreeNode node = new DefaultMutableTreeNode(codeJSP);
                    insert(node, 0);
                    return node;
                } else {
                    String markdownCode = code.getText();
                    htmlPane.setText(convertMarkdownToHtml(markdownCode));
                    remove(0);
                    DefaultMutableTreeNode node = new DefaultMutableTreeNode(htmlPane);
                    insert(node, 0);
                    return  node;
                }
            }
            return null;
        }
    }

    private ImageIcon createImageIcon(String base64Image) {
        try {
            base64Image = base64Image.replaceAll("[^a-zA-Z0-9+/=]", "");
            byte[] imageBytes = Base64.getDecoder().decode(base64Image);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return new ImageIcon(image);
        } catch (IOException e) {
            logger.debug("error parsing image: ", e);
            return null;
        }
    }

    private class NotebookTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Component component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            if (node.getUserObject() instanceof JComponent) {
                return (JComponent) node.getUserObject();
            }
            if (node instanceof CellNode) {
                switch (((CellNode)node).type) {
                    case CODE:
                    default:
                        setIcon(codeIcon);
                        break;
                    case MARKDOWN:
                        setIcon(textIcon);
                }
            } else if (node.isRoot()) setIcon(notebookIcon);
            return component;
        }
    }

    private class NotebookTreeCellEditor extends AbstractCellEditor implements TreeCellEditor {
        private final JTree tree;
        private RSyntaxTextArea editingTextArea;
        private DefaultMutableTreeNode editingNode;
        public NotebookTreeCellEditor(JTree tree) {
            this.tree = tree;
            addCellEditorListener(new CellEditorListener() {
                @Override
                public void editingStopped(ChangeEvent e) {
                    if (editingNode != null) {
                        if (editingNode.getParent() instanceof CellNode) {
                            CellNode p = (CellNode)editingNode.getParent();
                            editingNode = p.setMarkdownEdit(false);
                            treeModel.reload(p);
                            //treeModel.nodeStructureChanged(p);
                            //treeModel.nodeChanged(p);
                        }
                        treeModel.nodeChanged(editingNode);
                    }
                }

                @Override
                public void editingCanceled(ChangeEvent e) {
                    if (editingNode != null) {
                        if (editingNode.getParent() instanceof CellNode) {
                            CellNode p = (CellNode)editingNode.getParent();
                            editingNode = p.setMarkdownEdit(false);
                            treeModel.reload(p);
                            //treeModel.nodeStructureChanged(p);
                            //treeModel.nodeChanged(p);
                        }
                        treeModel.nodeChanged(editingNode);
                    }
                }
            });
        }

        @Override
        public Component getTreeCellEditorComponent(JTree tree, Object value, boolean isSelected, boolean expanded, boolean leaf, int row) {
            DefaultMutableTreeNode editingNode = (DefaultMutableTreeNode) value;
            if (editingNode.getParent() instanceof CellNode) {
                CellNode n = (CellNode)editingNode.getParent();
                if (n.type.equals(MARKDOWN) && !n.markdownEdit) {
                    n.setMarkdownEdit(true);
                    editingNode = (DefaultMutableTreeNode)n.getChildAt(0);
                    treeModel.nodeChanged(n);
                }
            }
            if (editingNode.getUserObject() instanceof RTextScrollPane) {
                RTextScrollPane scrollPane = (RTextScrollPane) editingNode.getUserObject();
                Component view = scrollPane.getViewport().getView();
                if (view instanceof RSyntaxTextArea) {
                    this.editingNode = editingNode;
                    if (editingNode instanceof CellNode) ((CellNode)editingNode).setMarkdownEdit(true);
                    editingTextArea = (RSyntaxTextArea) view;
                    editingTextArea.setEnabled(true);
                    editingTextArea.requestFocusInWindow();
                }
                return scrollPane;
            }
            return null;
        }

        @Override
        public Object getCellEditorValue() {
            if (editingTextArea != null) {
                editingTextArea.setEnabled(false);
                return editingTextArea.getText();
            }
            return null;
        }

        @Override
        public boolean isCellEditable(EventObject anEvent) {
            if (anEvent instanceof MouseEvent) {
                MouseEvent mouseEvent = (MouseEvent) anEvent;
                TreePath path = tree.getPathForLocation(mouseEvent.getX(), mouseEvent.getY());
                if (path != null) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    return node.getUserObject() instanceof RTextScrollPane && ((RTextScrollPane) node.getUserObject()).getViewport().getView() instanceof RSyntaxTextArea ||
                            node.getUserObject() instanceof JScrollPane && ((JScrollPane)node.getUserObject()).getViewport().getView() instanceof JEditorPane ||
                            node.getUserObject() instanceof JEditorPane;
                }
            }
            return false;
        }
    }

    private class NotebookMouseAdapter extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            if (SwingUtilities.isRightMouseButton(e)) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node instanceof CellNode) {
                        tree.setSelectionPath(path);
                        showCellPopupMenu(e);
                    } else if (node.isRoot()) showRootPopupMenu(e);
                }
            } else if (e.getClickCount() == 2) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (!node.isRoot() && node.getUserObject() instanceof JScrollPane) {
                        tree.startEditingAtPath(path);
                    }
                }
            }
        }

        private void showCellPopupMenu(MouseEvent e) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem addCellItem = new JMenuItem(new AbstractAction("Add Cell") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    createCell();
                }
            });
            JMenuItem removeCellItem = new JMenuItem(new AbstractAction("Remove Cell") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    removeCell();
                }
            });
            JMenuItem moveUpItem = new JMenuItem(new AbstractAction("Move Up") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    moveCellUp();
                }
            });
            JMenuItem moveDownItem = new JMenuItem(new AbstractAction("Move Down") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    moveCellDown();
                }
            });
            JMenu switchCellTypeMenu = new JMenu("Switch Cell Type");
            JMenuItem toMarkdownItem = new JMenuItem(new AbstractAction("To Markdown") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switchCellType(MARKDOWN);
                }
            });
            JMenuItem toCodeItem = new JMenuItem(new AbstractAction("To Code") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switchCellType(CODE);
                }
            });
            switchCellTypeMenu.add(toMarkdownItem);
            switchCellTypeMenu.add(toCodeItem);
            menu.add(addCellItem);
            menu.add(removeCellItem);
            menu.add(moveUpItem);
            menu.add(moveDownItem);
            menu.add(switchCellTypeMenu);
            menu.show(e.getComponent(), e.getX(), e.getY());
        }

        private void showRootPopupMenu(MouseEvent e) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem reload = new JMenuItem(new AbstractAction("Reload from Disk") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    loadNotebook(filePath);
                }
            });
            JMenuItem save = new JMenuItem(new AbstractAction("Save Changes") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    saveNotebook(filePath);
                }
            });
            menu.add(reload);
            menu.add(save);
            menu.show(e.getComponent(), e.getX(), e.getY());
        }

        private void createCell() {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
                CellNode newCellNode = new CellNode(CODE, language);
                treeModel.insertNodeInto(newCellNode, (MutableTreeNode) selectedNode.getParent(), selectedNode.getParent().getIndex(selectedNode) + 1);
            }
        }

        private void removeCell() {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
                treeModel.removeNodeFromParent(selectedNode);
            }
        }

        private void moveCellUp() {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
                DefaultMutableTreeNode parent = (DefaultMutableTreeNode) selectedNode.getParent();
                int index = parent.getIndex(selectedNode);
                if (index > 0) {
                    treeModel.removeNodeFromParent(selectedNode);
                    treeModel.insertNodeInto(selectedNode, parent, index - 1);
                }
            }
        }

        private void moveCellDown() {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
                DefaultMutableTreeNode parent = (DefaultMutableTreeNode) selectedNode.getParent();
                int index = parent.getIndex(selectedNode);
                if (index < parent.getChildCount() - 1) {
                    treeModel.removeNodeFromParent(selectedNode);
                    treeModel.insertNodeInto(selectedNode, parent, index + 1);
                }
            }
        }

        private void switchCellType(CELL_TYPE type) {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (selectedNode instanceof CellNode) {
                    CellNode cellNode = (CellNode)selectedNode;
                    if (cellNode.setType(type)) treeModel.reload(selectedNode);
                }
            }
        }

        private void saveNotebook(String filePath) {
            JSONArray cells = (JSONArray)notebook.get("cells");
            cells.clear();
            for (int i = 0; i < rootNode.getChildCount(); i++) {
                TreeNode n = rootNode.getChildAt(i);
                if (n instanceof CellNode) cells.add(((CellNode)n).getData());
            }
            try (FileWriter file = new FileWriter(filePath)) {
                String toSave = notebook.toJSONString();
                toSave = toSave.replace("\\/", "/");
                toSave = JSONUtils.prettyPrint(toSave, " ");
                file.write(toSave);
            } catch (IOException e) {
                logger.debug("error saving notebook", e);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JupyterNotebookViewer viewer = new JupyterNotebookViewer();
            viewer.setVisible(true);
        });
    }
}
