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

public class JupyterNotebookViewer {
    private static final Logger logger = LoggerFactory.getLogger(JupyterNotebookViewer.class);
    private static String[] supportedLanguages = new String[]{"python", "java"};
    private static ImageIcon codeIcon = loadIcon(JupyterNotebookViewer.class, "/icons/code24.png");
    private static ImageIcon textIcon = loadIcon(JupyterNotebookViewer.class, "/icons/text24.png");
    private static ImageIcon notebookIcon = loadIcon(JupyterNotebookViewer.class, "/icons/notebook32.png");

    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private JTree tree;
    private JSONObject notebook;
    private String language = "python";
    private RSyntaxTextArea description;
    private Runnable changeListener; // called when the notebook is edited
    private String savedSignature; // signature of the saved version: modified if the current signature differs

    public JupyterNotebookViewer(JSONObject notebook) {
        this.notebook = notebook;
        rootNode = new DefaultMutableTreeNode("Notebook");
        treeModel = new DefaultTreeModel(rootNode);
        tree = new JTree(treeModel);
        tree.setCellRenderer(new NotebookTreeCellRenderer());
        tree.setCellEditor(new NotebookTreeCellEditor(tree));
        tree.setEditable(true);
        tree.addMouseListener(new NotebookMouseAdapter());
        tree.setRootVisible(false);
        loadNotebook();
        savedSignature = getSignature();
    }

    /**
     * @param changeListener called each time the notebook is edited (text of a cell, cells added, removed, moved or type changed)
     */
    public JupyterNotebookViewer setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
        return this;
    }

    protected void fireChanged() {
        if (changeListener != null) changeListener.run();
    }

    /**
     * @return what can be edited in the viewer: type and text of each cell (outputs cannot be edited). Cheap to compute
     */
    public String getSignature() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            TreeNode n = rootNode.getChildAt(i);
            if (n instanceof CellNode) sb.append(((CellNode) n).type).append('\u0001').append(((CellNode) n).code.getText()).append('\u0002');
        }
        return sb.toString();
    }

    /**
     * @param savedSignature signature of the saved version of the notebook (when the displayed content already contains unsaved modifications)
     */
    public void setSavedSignature(String savedSignature) {
        this.savedSignature = savedSignature;
        fireChanged();
    }

    /**
     * The current content is the saved version
     */
    public void setSaved() {
        setSavedSignature(getSignature());
    }

    public boolean isModified() {
        return !getSignature().equals(savedSignature);
    }

    private void loadNotebook() {
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
    }

    public JTree getTree() {
        return tree;
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
            String type= ((String)data.get("cell_type")).toUpperCase().replace("RAW", "MARKDOWN");
            this.type = CELL_TYPE.valueOf(type);
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
            data.put("cell_type", CODE.equals(type) ? "code" : "markdown");
            data.put("source", new JSONArray());
            if (CODE.equals(type)) {
                data.put("execution_count", null);
                data.put("outputs", new JSONArray());
                data.put("metadata", createEmptyMetadata());
            } else data.put("metadata", new JSONObject());
            data.put("id", UUID.get().toHexString());
            createCodeNode("");
            code.setRows(3); // room to type in the empty cell
            code.setColumns(60);
            createOutputNodes();
        }

        public JSONObject getData() { // update code and return data
            data.put("source", toSourceLines(code.getText()));
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
                    data.put("cell_type", "code");
                    data.put("metadata", createEmptyMetadata());
                    data.put("execution_count", null);
                    data.put("outputs", new JSONArray());
                    data.remove("attachments");
                    return true;
                }
                case MARKDOWN: {
                    this.type = type;
                    code.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
                    data.put("cell_type", "markdown");
                    data.put("metadata", new JSONObject());
                    data.remove("outputs"); // not allowed in markdown cells
                    data.remove("execution_count");
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
            code.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { fireChanged(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { fireChanged(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
            });
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
            if (!CODE.equals(type)) return;
            Object outputs = data.get("outputs");
            if (!(outputs instanceof JSONArray)) return;
            for (Object outputObj : (JSONArray) outputs) {
                if (!(outputObj instanceof JSONObject)) continue;
                JComponent c = createOutputComponent((JSONObject) outputObj);
                if (c != null) add(new DefaultMutableTreeNode(c));
            }
        }

        public String getCodeString() {
            Object source = data.get("source");
            if (!(source instanceof java.util.List)) return source == null ? "" : source.toString();
            // nbformat: lines end with \n. Notebooks saved by previous versions of BACMMAN lost the line breaks: they are restored
            java.util.List<?> lines = (java.util.List<?>) source;
            StringBuilder sourceS = new StringBuilder();
            for (int i = 0; i< lines.size(); ++i) {
                String line = String.valueOf(lines.get(i));
                sourceS.append(line);
                if (i<lines.size()-1 && !line.endsWith("\n")) sourceS.append("\n");
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

    /**
     * @param text text of a cell
     * @return lines of the text as stored in notebooks: each line ends with \n except the last one; trailing line breaks are kept
     */
    static JSONArray toSourceLines(String text) {
        JSONArray source = new JSONArray();
        if (text == null || text.isEmpty()) return source;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; ++i) {
            if (i < lines.length - 1) source.add(lines[i] + "\n");
            else if (!lines[i].isEmpty()) source.add(lines[i]);
        }
        return source;
    }

    /**
     * @return text of a multiline field of a notebook: a string or a list of strings
     */
    static String multiline(Object o) {
        if (o == null) return null;
        if (o instanceof java.util.List) {
            StringBuilder sb = new StringBuilder();
            for (Object l : (java.util.List<?>) o) sb.append(l);
            return sb.toString();
        }
        return o.toString();
    }

    static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    static final Pattern STYLE_SCRIPT = Pattern.compile("(?is)<(style|script)\\b.*?</\\1\\s*>");

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * @return html displaying text as is (line breaks, spaces, characters such as < >), without terminal color codes
     */
    static String preformatted(String text, String color) {
        text = ANSI.matcher(text).replaceAll("");
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        return "<html><pre style=\"margin:0" + (color == null ? "" : ";color:" + color) + "\">" + escapeHtml(text) + "</pre></html>";
    }

    /**
     * html outputs (e.g. pandas tables) contain style sheets and scripts that Swing displays as text: they are removed
     */
    static String sanitizeHtml(String html) {
        return "<html>" + STYLE_SCRIPT.matcher(html).replaceAll("") + "</html>";
    }

    /**
     * Displays an output as Jupyter does: printed text (stream), error, or the richest representation of a result (html, image, then text)
     */
    JComponent createOutputComponent(JSONObject output) {
        String outputType = String.valueOf(output.get("output_type"));
        switch (outputType) {
            case "stream": {
                String text = multiline(output.get("text"));
                if (text == null || text.isEmpty()) return null;
                return new JLabel(preformatted(text, "stderr".equals(output.get("name")) ? "#a00000" : null));
            }
            case "error": {
                String text = output.get("ename") + ": " + output.get("evalue");
                Object tb = output.get("traceback");
                if (tb instanceof java.util.List && !((java.util.List<?>) tb).isEmpty()) text = String.join("\n", (java.util.List<String>) tb);
                return new JLabel(preformatted(text, "#a00000"));
            }
            default: { // execute_result, display_data
                Object d = output.get("data");
                if (!(d instanceof JSONObject)) return null;
                JSONObject data = (JSONObject) d;
                if (data.containsKey("text/html")) return new JLabel(sanitizeHtml(multiline(data.get("text/html"))));
                for (String imageType : new String[]{"image/png", "image/jpeg"}) {
                    if (data.containsKey(imageType)) {
                        ImageIcon imageIcon = createImageIcon(multiline(data.get(imageType)));
                        if (imageIcon != null) return new JLabel(imageIcon);
                    }
                }
                if (data.containsKey("text/plain")) return new JLabel(preformatted(multiline(data.get("text/plain")), null));
                if (data.isEmpty()) return null;
                java.util.List<String> types = new ArrayList<>();
                for (Object k : data.keySet()) types.add(String.valueOf(k));
                String kind = types.stream().anyMatch(k -> k.contains("plotly")) ? "plotly" : String.join(", ", types);
                return new JLabel("<html><i style=\"color:gray\">Interactive output (" + escapeHtml(kind) + "): open the notebook in Jupyter to display it</i></html>");
            }
        }
    }

    private ImageIcon createImageIcon(String base64Image) {
        try {
            base64Image = base64Image.replaceAll("[^a-zA-Z0-9+/=]", "");
            byte[] imageBytes = Base64.getDecoder().decode(base64Image);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return image == null ? null : new ImageIcon(image);
        } catch (IOException | IllegalArgumentException e) {
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
                    }
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

        private void createCell() {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
                CellNode newCellNode = new CellNode(CODE, language);
                treeModel.insertNodeInto(newCellNode, (MutableTreeNode) selectedNode.getParent(), selectedNode.getParent().getIndex(selectedNode) + 1);
                fireChanged();
            }
        }

        private void removeCell() {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
                treeModel.removeNodeFromParent(selectedNode);
                fireChanged();
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
                    fireChanged();
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
                    fireChanged();
                }
            }
        }

        private void switchCellType(CELL_TYPE type) {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (selectedNode instanceof CellNode) {
                    CellNode cellNode = (CellNode)selectedNode;
                    if (cellNode.setType(type)) {
                        treeModel.reload(selectedNode);
                        fireChanged();
                    }
                }
            }
        }

    }

    public JSONObject getContent() { // update changes to JSONArray and return it
        JSONArray cells = (JSONArray)notebook.get("cells");
        cells.clear();
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            TreeNode n = rootNode.getChildAt(i);
            if (n instanceof CellNode) cells.add(((CellNode)n).getData());
        }
        return notebook;
    }

    public static void saveNotebook(String filePath, JSONObject notebook) {
        try (Writer file = new OutputStreamWriter(new FileOutputStream(filePath), java.nio.charset.StandardCharsets.UTF_8)) {
            String toSave = notebook.toJSONString();
            toSave = toSave.replace("\\/", "/");
            toSave = JSONUtils.prettyPrint(toSave, " ");
            file.write(toSave);
        } catch (IOException e) {
            logger.debug("error saving notebook", e);
        }
    }
}
