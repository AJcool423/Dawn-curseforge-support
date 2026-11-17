package local.dawn.curseforge;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.lang.instrument.Instrumentation;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** Adds an independent Swing menu. No class transformation or account access. */
public final class AddonAgent {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final String MARKER = "local.dawn.curseforge.menuInstalled";
    private static final Color BACKGROUND = new Color(22, 24, 29);
    private static final Color FOREGROUND = new Color(230, 232, 237);
    private static final String PRIMARY_JAR = "desktop-desktop-0.2.0.506.jar";
    private static final String SUPPORTED_HASH = "f07ebe30d40fa2041818edab2af03ddf2247a3c765e437f7be4848ace24d50a2";
    private static CatalogPanel.CatalogBackend sharedBackend;

    private AddonAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        start();
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        start();
    }

    private static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread verifier = new Thread(() -> {
            if (!supportedBuild()) {
                System.err.println("[Dawn CurseForge] Unsupported Dawn build; add-on not attached.");
                return;
            }
            scheduleAttachment();
        }, "dawn-curseforge-version-check");
        verifier.setDaemon(true);
        verifier.start();
    }

    private static boolean supportedBuild() {
        try {
            ArrayList<Path> candidates = new ArrayList<>();
            String executable = System.getProperty("jpackage.app-path", "");
            if (!executable.isBlank()) candidates.add(Path.of(executable).toAbsolutePath().getParent().resolve("app").resolve(PRIMARY_JAR));
            for (String entry : System.getProperty("java.class.path", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank() && Path.of(entry).getFileName().toString().equals(PRIMARY_JAR)) candidates.add(Path.of(entry));
            }
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate)) continue;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(candidate)) {
                    byte[] buffer = new byte[65_536];
                    for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
                }
                return SUPPORTED_HASH.equals(HexFormat.of().formatHex(digest.digest()));
            }
        } catch (Exception ignored) { /* Fail closed without revealing local paths. */ }
        return false;
    }

    private static void scheduleAttachment() {
        try {
            SwingUtilities.invokeLater(() -> {
                Timer timer = new Timer(2_000, null);
                timer.addActionListener(event -> {
                    try {
                        for (Window window : Window.getWindows()) {
                            if (!(window instanceof JFrame frame) || !frame.isShowing()) continue;
                            if (!frame.getTitle().toLowerCase(Locale.ROOT).contains("dawn")) continue;
                            attach(frame);
                            timer.stop();
                            break;
                        }
                    } catch (Throwable failure) {
                        // Never propagate add-on failures into Dawn's event dispatch thread.
                        System.err.println("[Dawn CurseForge] Waiting for a usable launcher window ("
                                + failure.getClass().getSimpleName() + ").");
                    }
                });
                timer.setInitialDelay(0);
                timer.start();
            });
        } catch (Throwable failure) {
            System.err.println("[Dawn CurseForge] Add-on initialization was unavailable ("
                    + failure.getClass().getSimpleName() + ").");
        }
    }

    private static synchronized CatalogPanel.CatalogBackend backend() {
        if (sharedBackend == null) sharedBackend = BackendFactory.create();
        return sharedBackend;
    }

    private static void attach(JFrame owner) {
        if (Boolean.TRUE.equals(owner.getRootPane().getClientProperty(MARKER))) return;
        JMenuBar bar = owner.getJMenuBar();
        if (bar == null) {
            bar = new JMenuBar();
            bar.setBackground(BACKGROUND);
            bar.setForeground(FOREGROUND);
            bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            owner.setJMenuBar(bar);
        }
        JMenu menu = new JMenu("CurseForge");
        menu.setForeground(new Color(255, 166, 84));
        menu.setToolTipText("Local unofficial CurseForge add-on");
        JMenuItem open = new JMenuItem("Browse mods and modpacks…");
        open.setBackground(BACKGROUND);
        open.setForeground(FOREGROUND);
        final JDialog[] dialog = { null };
        open.addActionListener(event -> {
            try {
                if (dialog[0] != null && dialog[0].isDisplayable()) {
                    dialog[0].setVisible(true);
                    dialog[0].toFront();
                    return;
                }
                dialog[0] = createDialog(owner);
            } catch (Throwable failure) {
                System.err.println("[Dawn CurseForge] Could not open the add-on ("
                        + failure.getClass().getSimpleName() + ").");
            }
        });
        menu.add(open);
        bar.add(menu);
        owner.getRootPane().putClientProperty(MARKER, Boolean.TRUE);
        bar.revalidate();
        owner.revalidate();
        owner.repaint();
    }

    private static JDialog createDialog(JFrame owner) {
        JDialog dialog = new JDialog(owner, "CurseForge · Local Dawn add-on", false);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (dialog.getContentPane() instanceof CatalogPanel panel && panel.isBusy()) return;
                dialog.setVisible(false);
            }
        });
        dialog.getRootPane().registerKeyboardAction(event -> {
            if (dialog.getContentPane() instanceof CatalogPanel panel && panel.isBusy()) return;
            dialog.setVisible(false);
        }, javax.swing.KeyStroke.getKeyStroke("ESCAPE"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setMinimumSize(new Dimension(860, 620));
        dialog.setSize(1_050, 740);
        JPanel loading = new JPanel(new BorderLayout());
        loading.setBackground(BACKGROUND);
        loading.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JLabel label = new JLabel("Opening the local CurseForge add-on…");
        label.setForeground(FOREGROUND);
        loading.add(label, BorderLayout.NORTH);
        dialog.setContentPane(loading);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        new SwingWorker<CatalogPanel.CatalogBackend, Void>() {
            @Override protected CatalogPanel.CatalogBackend doInBackground() throws Exception {
                return backend();
            }
            @Override protected void done() {
                if (!dialog.isDisplayable()) return;
                try {
                    dialog.setContentPane(new CatalogPanel(get()));
                    dialog.revalidate();
                    dialog.repaint();
                } catch (Exception failure) {
                    label.setText("The add-on backend could not start. Close this window and check the add-on setup.");
                }
            }
        }.execute();
        return dialog;
    }
}
