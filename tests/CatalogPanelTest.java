package local.dawn.curseforge;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Headless component fixture. No network requests, real keys, or Dawn writes. */
public final class CatalogPanelTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        FakeBackend backend = new FakeBackend();
        CatalogPanel panel = edt(() -> new CatalogPanel(backend));
        idle(panel);
        check(edt(() -> button(panel, "Search").isEnabled()), "Available backend enables search");

        edt(() -> { button(panel, "Search").doClick(); return null; });
        idle(panel);
        selectResult(panel);
        idle(panel);
        check(!edt(() -> button(panel, "Install selected file").isEnabled()), "A mod needs a target profile");

        edt(() -> { type(panel).setSelectedItem("Modpacks"); button(panel, "Search").doClick(); return null; });
        idle(panel);
        selectResult(panel);
        idle(panel);
        check(edt(() -> button(panel, "Install selected file").isEnabled()), "A modpack can create a profile without a target");
        backend.installGate = new CountDownLatch(1);
        edt(() -> { button(panel, "Install selected file").doClick(); return null; });
        check(backend.installStarted.await(5, TimeUnit.SECONDS), "Install starts off the EDT");
        waitStatus(panel, "Fixture transfer in progress");
        check(edt(panel::isBusy), "Installation keeps panel busy");
        check(!edt(() -> button(panel, "Install selected file").isEnabled()), "Cannot start a competing installation");
        backend.installGate.countDown();
        idle(panel);
        check(backend.lastProfile == null && backend.lastItem.modpack(), "Modpack passes null target to create a new profile");
        check(edt(() -> status(panel).getText().equals("Fixture installation completed")), "Completion replaces progress");

        backend.withProfile = true;
        edt(() -> { button(panel, "Refresh profiles").doClick(); return null; });
        idle(panel);
        edt(() -> { type(panel).setSelectedItem("Mods"); button(panel, "Search").doClick(); return null; });
        idle(panel);
        check("1.21.1".equals(backend.lastVersion) && "fabric".equals(backend.lastLoader), "Mod search uses the selected profile filters");
        selectResult(panel);
        idle(panel);
        edt(() -> { button(panel, "Install selected file").doClick(); return null; });
        idle(panel);
        check(backend.lastProfile != null && "fixture-profile".equals(backend.lastProfile.id()), "Mods install into selected profile");

        edt(() -> {
            JPasswordField password = descendants(panel, JPasswordField.class).get(0);
            password.setText("fixture-only-value");
            button(panel, "Use key for session").doClick();
            check(password.getPassword().length == 0, "Masked field clears before background work");
            return null;
        });
        idle(panel);
        check("fixture-only-value".equals(backend.key), "Key was passed to the session backend");

        backend.failNextSearch = true;
        edt(() -> { button(panel, "Search").doClick(); return null; });
        idle(panel);
        check(edt(() -> status(panel).getText().contains("Fixture catalog unavailable")), "Background failure is visible");
        check(edt(() -> button(panel, "Search").isEnabled()), "Error releases controls for retry");
        check(backend.calls > 10, "Fixture exercised backend operations");
        System.out.println("PASS CatalogPanel: off-EDT backend calls, profiles/filters, mod and new-pack targets, session key clearing, busy state, progress and errors.");
    }

    private static final class FakeBackend implements CatalogPanel.CatalogBackend {
        volatile boolean withProfile, failNextSearch;
        volatile String key, lastVersion, lastLoader;
        volatile CatalogPanel.Profile lastProfile;
        volatile CatalogPanel.Item lastItem;
        volatile CountDownLatch installGate = new CountDownLatch(0);
        final CountDownLatch installStarted = new CountDownLatch(1);
        int calls;
        private void background() { check(!SwingUtilities.isEventDispatchThread(), "Backend call must be off EDT"); calls++; }
        public boolean available() { background(); return true; }
        public void setApiKey(String value) { background(); key = value; }
        public List<CatalogPanel.Profile> profiles() { background(); return withProfile ? List.of(new CatalogPanel.Profile("fixture-profile", "Fixture profile", "1.21.1", "fabric")) : List.of(); }
        public List<CatalogPanel.Item> search(String query, String type, String version, String loader) {
            background(); lastVersion = version; lastLoader = loader;
            if (failNextSearch) { failNextSearch = false; throw new IllegalStateException("Fixture catalog unavailable"); }
            return List.of(new CatalogPanel.Item(1, "Fixture content", "Fixture description", "modpack".equals(type)));
        }
        public List<CatalogPanel.FileItem> files(CatalogPanel.Item item, String version, String loader) { background(); return List.of(new CatalogPanel.FileItem(2, "Fixture compatible file")); }
        public String install(CatalogPanel.Item item, CatalogPanel.FileItem file, CatalogPanel.Profile profile, Consumer<String> progress) throws Exception {
            background(); lastItem = item; lastProfile = profile; progress.accept("Fixture transfer in progress"); installStarted.countDown();
            check(installGate.await(5, TimeUnit.SECONDS), "Install fixture released");
            return "Fixture installation completed";
        }
    }

    private static JButton button(Container root, String text) { return descendants(root, JButton.class).stream().filter(item -> text.equals(item.getText())).findFirst().orElseThrow(); }
    private static JComboBox<?> type(Container root) { return descendants(root, JComboBox.class).stream().filter(item -> item.getItemCount() > 0 && "Mods".equals(item.getItemAt(0))).findFirst().orElseThrow(); }
    private static JTextArea status(Container root) { return descendants(root, JTextArea.class).stream().filter(item -> "Add-on status".equals(item.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow(); }
    private static void selectResult(CatalogPanel panel) throws Exception { edt(() -> { descendants(panel, JList.class).stream().filter(list -> "CurseForge search results".equals(list.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().setSelectedIndex(0); return null; }); }
    private static <T> List<T> descendants(Container root, Class<T> type) { List<T> found = new ArrayList<>(); for (Component child : root.getComponents()) { if (type.isInstance(child)) found.add(type.cast(child)); if (child instanceof Container nested) found.addAll(descendants(nested, type)); } return found; }
    private static void idle(CatalogPanel panel) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (edt(panel::isBusy)) { if (System.nanoTime() > end) throw new AssertionError("Panel did not become idle"); Thread.sleep(15); } }
    private static void waitStatus(CatalogPanel panel, String text) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); while (!edt(() -> status(panel).getText().equals(text))) { if (System.nanoTime() > end) throw new AssertionError("Progress status not observed"); Thread.sleep(15); } }
    private static <T> T edt(Callable<T> operation) throws Exception { AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>(); SwingUtilities.invokeAndWait(() -> { try { value.set(operation.call()); } catch (Throwable failure) { error.set(failure); } }); if (error.get() != null) throw new AssertionError("EDT operation failed", error.get()); return value.get(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
