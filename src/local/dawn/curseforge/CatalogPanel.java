package local.dawn.curseforge;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** All service calls run outside the EDT; only this panel's components are themed. */
public final class CatalogPanel extends JPanel {
    public record Item(long id, String name, String summary, boolean modpack) {
        @Override public String toString() { return name; }
    }
    public record FileItem(long id, String name) {
        @Override public String toString() { return name; }
    }
    public record Profile(String id, String name, String version, String loader) {
        @Override public String toString() { return name + " · " + version + " · " + loader; }
    }
    public interface CatalogBackend {
        boolean available() throws Exception;
        void setApiKey(String key) throws Exception;
        List<Item> search(String query, String type, String mcVersion, String loader) throws Exception;
        List<FileItem> files(Item item, String mcVersion, String loader) throws Exception;
        List<Profile> profiles() throws Exception;
        String install(Item item, FileItem file, Profile profile, Consumer<String> progress) throws Exception;
    }

    private static final Color BG = new Color(22, 24, 29), PANEL = new Color(29, 32, 39);
    private static final Color TEXT = new Color(232, 234, 239), MUTED = new Color(160, 169, 183);
    private static final Color ACCENT = new Color(255, 161, 77), LINE = new Color(58, 63, 74);
    private static final String KEY_HELP = "https://support.curseforge.com/support/solutions/articles/9000208346-about-the-curseforge-api-and-how-to-apply-for-a-key";
    private final CatalogBackend backend;
    private final JTextField query = new JTextField(24), version = new JTextField(9);
    private final JComboBox<String> type = new JComboBox<>(new String[]{"Mods", "Modpacks"});
    private final JComboBox<String> loader = new JComboBox<>(new String[]{"Any", "Fabric", "Forge", "NeoForge"});
    private final JPasswordField key = new JPasswordField(22);
    private final JButton search = button("Search", true), useKey = button("Use key for session", false);
    private final JButton install = button("Install selected file", true), reload = button("Refresh profiles", false);
    private final DefaultListModel<Item> items = new DefaultListModel<>();
    private final JList<Item> results = new JList<>(items);
    private final JComboBox<FileItem> files = new JComboBox<>();
    private final JComboBox<Profile> profiles = new JComboBox<>();
    private final JTextArea description = textArea("Select a catalog result to load its compatible files.");
    private final JTextArea status = textArea("Checking available catalog access and Dawn profiles…");
    private final JLabel availability = label("Checking catalog access…", MUTED);
    private final JProgressBar progress = new JProgressBar();
    private boolean busy, ready, updating;

    public CatalogPanel(CatalogBackend backend) {
        super(new BorderLayout(0, 15));
        this.backend = Objects.requireNonNull(backend);
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        bindActions();
        loadInitialState();
    }

    public boolean isBusy() { return busy; }

    private JPanel header() {
        JPanel heading = panel(new BorderLayout(0, 10));
        JPanel title = panel(new BorderLayout());
        JLabel name = label("CurseForge", ACCENT); name.setFont(name.getFont().deriveFont(Font.BOLD, 25f));
        title.add(name, BorderLayout.WEST);
        title.add(label("LOCAL · UNOFFICIAL DAWN ADD-ON", MUTED), BorderLayout.EAST);
        heading.add(title, BorderLayout.NORTH);
        JPanel setup = panel(new GridBagLayout());
        GridBagConstraints c = constraints(); c.gridwidth = 4; c.weightx = 1;
        setup.add(availability, c);
        c.gridy++; c.gridwidth = 1; c.weightx = 0;
        JLabel keyLabel = label("CurseForge API key", MUTED); keyLabel.setLabelFor(key); setup.add(keyLabel, c);
        c.gridx = 1; c.weightx = 1; styleInput(key); key.setToolTipText("Used only for this process session; never saved by the add-on UI."); setup.add(key, c);
        c.gridx = 2; c.weightx = 0; setup.add(useKey, c);
        c.gridx = 3; JButton help = button("API key help ↗", false); help.addActionListener(event -> openHelp()); setup.add(help, c);
        heading.add(setup, BorderLayout.CENTER);
        return heading;
    }

    private JPanel content() {
        JPanel body = panel(new BorderLayout(0, 14));
        JPanel filters = panel(new GridBagLayout()); GridBagConstraints c = constraints();
        addField(filters, c, 0, "Search", query, 1);
        addField(filters, c, 1, "Content", type, 0);
        addField(filters, c, 2, "Minecraft version", version, 0);
        addField(filters, c, 3, "Loader", loader, 0);
        c.gridx = 4; c.gridy = 1; c.weightx = 0; filters.add(search, c);
        version.setToolTipText("Leave blank for all versions; selecting a profile fills its version and loader.");
        body.add(filters, BorderLayout.NORTH);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); results.setFixedCellHeight(38);
        results.setBackground(PANEL); results.setForeground(TEXT); results.setCellRenderer(renderer());
        results.getAccessibleContext().setAccessibleName("CurseForge search results");
        JScrollPane list = scroll(results); list.setPreferredSize(new Dimension(370, 280));
        JPanel details = panel(new BorderLayout(0, 12));
        details.setBorder(BorderFactory.createEmptyBorder(4, 15, 4, 0));
        JScrollPane summary = scroll(description); summary.setPreferredSize(new Dimension(310, 140));
        details.add(summary, BorderLayout.CENTER);
        JPanel controls = panel(new GridBagLayout()); c = constraints();
        c.weightx = 1; c.gridwidth = 2;
        JLabel fileLabel = label("Compatible file", MUTED); fileLabel.setLabelFor(files); controls.add(fileLabel, c);
        c.gridy++; styleInput(files); files.setRenderer(renderer()); controls.add(files, c);
        c.gridy++; JLabel profileLabel = label("Mods install into this profile; packs create a new profile", MUTED); profileLabel.setLabelFor(profiles); controls.add(profileLabel, c);
        c.gridy++; styleInput(profiles); profiles.setRenderer(renderer()); controls.add(profiles, c);
        c.gridy++; c.gridwidth = 1; c.weightx = 0; controls.add(reload, c); c.gridx = 1; c.weightx = 1; controls.add(install, c);
        details.add(controls, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, details);
        split.setResizeWeight(.43); split.setDividerSize(6); split.setBorder(null); split.setBackground(BG);
        body.add(split, BorderLayout.CENTER);
        return body;
    }

    private JPanel footer() {
        JPanel bottom = panel(new BorderLayout(0, 9));
        progress.setIndeterminate(false); progress.setBorderPainted(false); progress.setBackground(PANEL); progress.setForeground(ACCENT);
        progress.setPreferredSize(new Dimension(100, 4)); bottom.add(progress, BorderLayout.NORTH);
        status.setRows(3); status.getAccessibleContext().setAccessibleName("Add-on status"); bottom.add(scroll(status), BorderLayout.CENTER);
        bottom.add(label("This add-on does not change Dawn accounts, advertisements, or partner integrations.", MUTED), BorderLayout.SOUTH);
        return bottom;
    }

    private void bindActions() {
        search.addActionListener(event -> searchCatalog()); query.addActionListener(event -> searchCatalog());
        useKey.addActionListener(event -> applyKey()); reload.addActionListener(event -> reloadProfiles());
        results.addListSelectionListener(event -> { if (!event.getValueIsAdjusting() && !updating && !busy) loadFiles(); });
        files.addActionListener(event -> updateControls());
        profiles.addActionListener(event -> { if (!updating && !busy) { fillProfileFilters(); if (results.getSelectedValue() != null) loadFiles(); } updateControls(); });
        install.addActionListener(event -> installSelected());
        type.addActionListener(event -> { invalidateResults(); if (type.getSelectedIndex() == 0) fillProfileFilters(); else { updating = true; version.setText(""); loader.setSelectedIndex(0); updating = false; } });
        loader.addActionListener(event -> { if (!updating && !busy && results.getSelectedValue() != null) loadFiles(); });
        version.addActionListener(event -> { if (results.getSelectedValue() != null) loadFiles(); });
    }

    private void loadInitialState() {
        work("Checking catalog access and profiles…", () -> new Initial(backend.available(), backend.profiles()), initial -> {
            ready = initial.available(); setProfiles(initial.profiles()); showAvailability();
            setStatus(ready ? "Catalog access is available. Search to find mods or modpacks." : setupMessage(), false);
        });
    }

    private void searchCatalog() {
        if (busy || !ready) return;
        String text = query.getText().trim(), kind = type.getSelectedIndex() == 0 ? "mod" : "modpack";
        String mc = version.getText().trim(), selectedLoader = selectedLoader();
        invalidateResults();
        work("Searching CurseForge…", () -> backend.search(text, kind, mc, selectedLoader), found -> {
            updating = true; items.clear(); found.forEach(items::addElement); updating = false;
            setStatus(found.isEmpty() ? "No matching results. Try a different search or version." : found.size() + " results. Select one to see compatible files.", false);
        });
    }

    private void loadFiles() {
        Item item = results.getSelectedValue(); if (item == null || busy) return;
        String mc = version.getText().trim(), selectedLoader = selectedLoader();
        files.setModel(new DefaultComboBoxModel<>()); description.setText(item.name() + "\n\n" + Objects.toString(item.summary(), ""));
        work("Loading compatible files for " + item.name() + "…", () -> backend.files(item, mc, selectedLoader), found -> {
            files.setModel(new DefaultComboBoxModel<>(found.toArray(FileItem[]::new)));
            setStatus(found.isEmpty() ? "No compatible files were returned for these filters. Try another version or loader." : item.modpack() ? "Choose a file. Installing this pack creates a new Dawn profile." : "Choose a file and an existing Dawn profile before installing.", false);
        });
    }

    private void applyKey() {
        if (busy) return; char[] characters = key.getPassword(); String sessionKey = new String(characters).trim(); Arrays.fill(characters, '\0'); key.setText("");
        if (sessionKey.isEmpty()) { setStatus("Enter an API key before applying it. Existing catalog access was not changed.", true); return; }
        work("Checking session catalog access…", () -> { backend.setApiKey(sessionKey); return backend.available(); }, available -> {
            ready = available; showAvailability(); setStatus(ready ? "Minecraft catalog access verified. Search to get started. Individual downloads may have additional restrictions." : setupMessage(), !ready);
        });
    }

    private void reloadProfiles() { if (!busy) work("Refreshing Dawn profiles…", backend::profiles, found -> { setProfiles(found); setStatus(found.isEmpty() ? "No compatible Dawn profiles were found. Create a profile in Dawn, then refresh." : "Dawn profiles refreshed.", false); }); }

    private void installSelected() {
        Item item = results.getSelectedValue(); FileItem file = (FileItem) files.getSelectedItem(); Profile profile = (Profile) profiles.getSelectedItem();
        if (busy || item == null || file == null || (!item.modpack() && profile == null)) return;
        Profile target = item.modpack() ? null : profile;
        work("Installing " + item.name() + (target == null ? " as a new Dawn profile…" : " into " + target.name() + "…"), () -> backend.install(item, file, target, message -> SwingUtilities.invokeLater(() -> setStatus(message, false))), message -> setStatus(message, false));
    }

    private <T> void work(String message, Callable<T> operation, Consumer<T> success) {
        if (busy) return; busy = true; setStatus(message, false); progress.setIndeterminate(true); updateControls();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return operation.call(); }
            @Override protected void done() {
                try { success.accept(get()); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); setStatus("The operation was interrupted. Try again.", true); }
                catch (ExecutionException failure) { setStatus(errorMessage(failure.getCause()), true); }
                catch (RuntimeException failure) { setStatus(errorMessage(failure), true); }
                finally { busy = false; progress.setIndeterminate(false); updateControls(); }
            }
        }.execute();
    }

    private void setProfiles(List<Profile> found) { updating = true; profiles.setModel(new DefaultComboBoxModel<>(found.toArray(Profile[]::new))); fillProfileFilters(); updating = false; }
    private void fillProfileFilters() { Profile profile = (Profile) profiles.getSelectedItem(); if (profile == null || type.getSelectedIndex() != 0) return; updating = true; version.setText(Objects.toString(profile.version(), "")); loader.setSelectedIndex(0); for (int i = 1; i < loader.getItemCount(); i++) if (loader.getItemAt(i).equalsIgnoreCase(profile.loader())) loader.setSelectedIndex(i); updating = false; }
    private String selectedLoader() { return loader.getSelectedIndex() == 0 ? "" : Objects.toString(loader.getSelectedItem(), "").toLowerCase(Locale.ROOT); }
    private void invalidateResults() { if (busy) return; updating = true; items.clear(); files.setModel(new DefaultComboBoxModel<>()); updating = false; description.setText("Select a catalog result to load its compatible files."); updateControls(); }
    private void showAvailability() { availability.setText(ready ? "Minecraft catalog access verified · key stays in this session" : "Catalog access is not configured. Use an approved CurseForge API key for this session."); availability.setForeground(ready ? new Color(146, 205, 163) : MUTED); }
    private String setupMessage() { return "No usable catalog access was found. Obtain an approved CurseForge API key using API key help, then choose Use key for session. The add-on does not save the key or show a placeholder catalog."; }
    private void updateControls() { search.setEnabled(!busy && ready); useKey.setEnabled(!busy); key.setEnabled(!busy); reload.setEnabled(!busy); query.setEnabled(!busy); type.setEnabled(!busy); version.setEnabled(!busy); loader.setEnabled(!busy); results.setEnabled(!busy); files.setEnabled(!busy); profiles.setEnabled(!busy && type.getSelectedIndex() == 0); Item item = results.getSelectedValue(); install.setEnabled(!busy && ready && item != null && files.getSelectedItem() != null && (item.modpack() || profiles.getSelectedItem() != null)); }
    private void setStatus(String text, boolean error) { status.setForeground(error ? new Color(244, 169, 169) : MUTED); status.setText(Objects.toString(text, "Operation finished.")); status.setCaretPosition(0); }
    private static String errorMessage(Throwable failure) { if (failure == null) return "The operation failed. Check the add-on setup and try again."; String text = failure.getMessage(); return text == null || text.isBlank() ? "The operation failed (" + failure.getClass().getSimpleName() + ")." : text; }
    private void openHelp() { if (busy) return; work("Opening the official CurseForge API key guide…", () -> { if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) throw new IllegalStateException("No desktop browser is available. Visit CurseForge Support for the API key guide."); Desktop.getDesktop().browse(URI.create(KEY_HELP)); return true; }, ignored -> setStatus("The official CurseForge API key guide opened in your browser.", false)); }
    private record Initial(boolean available, List<Profile> profiles) {}
    private static JPanel panel(java.awt.LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setBackground(BG); return panel; }
    private static JLabel label(String text, Color color) { JLabel label = new JLabel(text); label.setForeground(color); return label; }
    private static JButton button(String text, boolean primary) { JButton button = new JButton(text); button.setBackground(primary ? ACCENT : PANEL); button.setForeground(primary ? new Color(38, 27, 18) : TEXT); button.setOpaque(true); button.setFocusPainted(true); button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(primary ? ACCENT : LINE), BorderFactory.createEmptyBorder(9, 12, 9, 12))); return button; }
    private static JTextArea textArea(String text) { JTextArea area = new JTextArea(text); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setBackground(PANEL); area.setForeground(MUTED); area.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13)); return area; }
    private static void styleInput(JComponent component) { component.setBackground(PANEL); component.setForeground(TEXT); component.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(6, 7, 6, 7))); if (component instanceof JTextField field) field.setCaretColor(ACCENT); }
    private static JScrollPane scroll(Component component) { JScrollPane scroll = new JScrollPane(component); scroll.setBorder(BorderFactory.createLineBorder(LINE)); scroll.setBackground(PANEL); scroll.getViewport().setBackground(PANEL); return scroll; }
    private static GridBagConstraints constraints() { GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = 0; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 0, 4, 9); return c; }
    private static void addField(JPanel panel, GridBagConstraints c, int x, String text, JComponent field, double weight) { c.gridx = x; c.gridy = 0; c.weightx = weight; JLabel label = label(text, MUTED); label.setLabelFor(field); panel.add(label, c); c.gridy = 1; styleInput(field); panel.add(field, c); field.getAccessibleContext().setAccessibleName(text); }
    private static DefaultListCellRenderer renderer() { return new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) { putClientProperty("html.disable", Boolean.TRUE); super.getListCellRendererComponent(list, value, index, selected, focus); setBackground(selected ? new Color(73, 51, 32) : PANEL); setForeground(selected ? new Color(255, 197, 143) : TEXT); setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 9)); return this; } }; }
}
