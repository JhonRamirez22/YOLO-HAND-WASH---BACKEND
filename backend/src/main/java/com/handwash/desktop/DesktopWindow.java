package com.handwash.desktop;

import com.handwash.model.WashStep;
import com.handwash.model.WhoAction;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;
import javax.swing.JButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Native, local-only Swing view for the hand-washing station. */
final class DesktopWindow extends JFrame {
    private static final Color NAVY = new Color(18, 40, 62);
    private static final Color TEAL = new Color(0, 128, 128);
    private static final Color MUTED = new Color(92, 108, 120);
    private static final Color BACKGROUND = new Color(245, 248, 250);

    private final VideoPanel video = new VideoPanel();
    private final JLabel cameraState = new JLabel("Cámara: esperando capturador");
    private final JButton linkIphone = new JButton("Enlazar iPhone");
    private final JLabel cameraLinkMessage = new JLabel(" ");
    private final JLabel localHands = new JLabel("Manos detectadas por YOLO: —");
    private final JLabel warmupState = new JLabel("Preparación: —");
    private final JLabel sessionState = new JLabel("Sesión: esperando YOLO");
    private final JLabel currentStep = new JLabel("Esperando inicio");
    private final JLabel intentState = new JLabel("Intención: —");
    private final JLabel backendHands = new JLabel("Manos recibidas por Java: —");
    private final JLabel geometryShadow = new JLabel("<html>Sombra geométrica: apagada</html>");
    private final JLabel elapsed = new JLabel("Tiempo activo: 00:00");
    private final JLabel issue = new JLabel(" ");
    private final JLabel progressText = new JLabel("0 de 6 movimientos de fricción");
    private final JLabel scopeStatus = new JLabel("Solo se evalúan seis movimientos de fricción.");
    private final JProgressBar progress = new JProgressBar(0, 6);
    private final JLabel[] stepRows = new JLabel[WhoAction.SECUENCIA.size()];
    private final JTextArea diagnostics = new JTextArea("Diagnóstico: esperando observaciones", 5, 30);
    private final JTextArea resetEvents = new JTextArea(3, 30);
    private final AtomicReference<BufferedImage> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean frameUpdateQueued = new AtomicBoolean();

    DesktopWindow() {
        super("HandWash — estación local");
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 680));
        setSize(1280, 800);
        setLocationByPlatform(true);
        getContentPane().setBackground(BACKGROUND);
        setLayout(new BorderLayout(0, 0));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildMain(), BorderLayout.CENTER);
    }

    void updateCamera(DesktopDashboard.CameraView state) {
        runOnEdt(() -> {
            String label = switch (state.state()) {
                case "connected" -> "Cámara de Continuidad: conectada";
                case "reconnecting" -> "Cámara desconectada; reconectando…";
                default -> "Cámara: capturador no disponible";
            };
            cameraState.setText(label);
            localHands.setText("Manos detectadas por YOLO: " + state.handsVisible());
            if (state.stepsEnabled()) {
                warmupState.setText("Inferencia habilitada; Java debe confirmar el inicio");
            } else if (state.warmupRequiredMs() > 0 && !state.warmupComplete()) {
                warmupState.setText("Preparación: " + seconds(state.warmupElapsedMs()) + " / "
                    + seconds(state.warmupRequiredMs()) + " s");
            } else if (state.warmupComplete()) {
                warmupState.setText("Preparación completa; estabilizando presencia");
            } else {
                warmupState.setText("Preparación: esperando ambas manos");
            }
            if (!"connected".equals(state.state())) video.setPlaceholder(label);
            else if (!video.hasFrame()) video.setPlaceholder("Cámara conectada; esperando video…");
        });
    }

    void setCameraLinkAction(Runnable action) {
        linkIphone.addActionListener(event -> {
            linkIphone.setEnabled(false);
            cameraLinkMessage.setText("Buscando cámara de Continuidad…");
            action.run();
        });
    }

    void showCameraLinkResult(String message) {
        runOnEdt(() -> {
            cameraLinkMessage.setText("<html><body style='width:320px'>" + message + "</body></html>");
            linkIphone.setEnabled(true);
        });
    }

    void updateSession(DesktopDashboard.SessionView state) {
        runOnEdt(() -> {
            if (state == null) {
                sessionState.setText("Sesión: esperando YOLO");
                currentStep.setText("Esperando inicio");
                intentState.setText("Intención: —");
                backendHands.setText("Manos recibidas por Java: —");
                geometryShadow.setText("<html>Sombra geométrica: apagada</html>");
                elapsed.setText("Tiempo activo: 00:00");
                issue.setText(" ");
                progress.setValue(0);
                progress.setMaximum(WashStep.getMovimientosFriccionSoportados());
                progressText.setText("0 de 6 movimientos de fricción");
                scopeStatus.setText("<html><body style='width:320px'>Solo se evalúan seis movimientos de fricción; no se verifica el lavado completo.</body></html>");
                updateStepRows(0, 0, List.of(
                    "1. Palmas", "2. Dorsos", "3. Interdigitales", "4. Nudillos",
                    "5. Pulgar", "6. Punta de dedos"));
                return;
            }

            sessionState.setText("Sesión " + shortId(state.sessionId()) + " · "
                + humanize(state.status()) + " · " + humanize(state.protocol()));
            currentStep.setText(state.stepLabel());
            intentState.setText("Intención: " + humanize(state.intent()));
            backendHands.setText("Manos recibidas por Java: " + state.handsVisible()
                + (state.reason().isBlank() ? "" : " · " + state.reason()));
            geometryShadow.setText("<html>Sombra geométrica (no acredita): "
                + state.geometryShadow().replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;")
                + "</html>");
            elapsed.setText("Tiempo activo: " + duration(state.activeMs()));
            issue.setText(state.issue().isBlank() ? " " : "Última observación: " + state.issue());
            int total = Math.max(1, state.stepsTotal());
            int completed = Math.max(0, Math.min(total, state.stepsCompleted()));
            progress.setMaximum(total);
            progress.setValue(completed);
            boolean frictionMode = !"PROTOCOLO_OMS".equals(state.evaluationMode());
            progressText.setText(completed + " de " + total
                + (frictionMode ? " movimientos de fricción" : " acciones OMS"));
            scopeStatus.setText(frictionMode
                ? state.frictionSequenceObservedComplete()
                    ? "<html><body style='width:320px'>6/6 movimientos observados. No equivale al lavado OMS completo ni es aprobación clínica.</body></html>"
                    : "<html><body style='width:320px'>Modo parcial: no evalúa mojado, jabón, enjuague, secado ni cierre del grifo.</body></html>"
                : "<html><body style='width:320px'>Secuencia OMS observada; no representa aprobación clínica.</body></html>");
            updateStepRows(completed, state.currentStepNumber(), state.stepLabels());
        });
    }

    void showFrame(BufferedImage frame) {
        pendingFrame.set(frame);
        scheduleFrameUpdate();
    }

    void updateDiagnostics(String text) {
        runOnEdt(() -> diagnostics.setText(text));
    }

    void updateResetEvents(String text) {
        runOnEdt(() -> resetEvents.setText(text));
    }

    private void scheduleFrameUpdate() {
        if (frameUpdateQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                BufferedImage latest = pendingFrame.getAndSet(null);
                if (latest != null) video.setFrame(latest);
                frameUpdateQueued.set(false);
                if (pendingFrame.get() != null) scheduleFrameUpdate();
            });
        }
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(NAVY);
        header.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
        JLabel title = new JLabel("Lavado de manos · monitoreo local");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JLabel mode = new JLabel("DEMOSTRACIÓN · NO CLÍNICA");
        mode.setForeground(new Color(255, 221, 125));
        mode.setFont(mode.getFont().deriveFont(Font.BOLD, 12f));
        header.add(title, BorderLayout.WEST);
        header.add(mode, BorderLayout.EAST);
        return header;
    }

    private JPanel buildMain() {
        JPanel main = new JPanel(new BorderLayout(18, 0));
        main.setOpaque(false);
        main.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        video.setPreferredSize(new Dimension(760, 480));
        main.add(video, BorderLayout.CENTER);

        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setPreferredSize(new Dimension(350, 0));
        details.setLayout(new javax.swing.BoxLayout(details, javax.swing.BoxLayout.Y_AXIS));
        details.add(sectionTitle("Estado en vivo"));
        details.add(spaced(cameraState));
        details.add(linkIphone);
        details.add(spaced(cameraLinkMessage));
        details.add(spaced(localHands));
        details.add(spaced(warmupState));
        details.add(spaced(sessionState));
        details.add(spaced(currentStep));
        details.add(spaced(intentState));
        details.add(spaced(backendHands));
        geometryShadow.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        geometryShadow.setForeground(MUTED);
        details.add(geometryShadow);
        details.add(spaced(elapsed));
        details.add(spaced(issue));
        diagnostics.setEditable(false);
        diagnostics.setLineWrap(true);
        diagnostics.setWrapStyleWord(true);
        JScrollPane debugPanel = new JScrollPane(diagnostics);
        debugPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        details.add(debugPanel);
        resetEvents.setEditable(false);
        resetEvents.setLineWrap(true);
        JScrollPane resetPanel = new JScrollPane(resetEvents);
        resetPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        details.add(resetPanel);
        details.add(sectionTitle("Secuencia de movimientos"));
        scopeStatus.setForeground(MUTED);
        scopeStatus.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        scopeStatus.setAlignmentX(LEFT_ALIGNMENT);
        details.add(scopeStatus);
        progress.setStringPainted(false);
        progress.setForeground(TEAL);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
        details.add(progress);
        progressText.setForeground(MUTED);
        details.add(spaced(progressText));
        for (int i = 0; i < stepRows.length; i++) {
            stepRows[i] = new JLabel();
            stepRows[i].setFont(stepRows[i].getFont().deriveFont(13f));
            stepRows[i].setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            details.add(stepRows[i]);
        }
        JLabel stopHint = new JLabel("<html>Cerrar esta ventana solo la oculta.<br>Ctrl+C en la terminal detiene cámara y Java.</html>");
        stopHint.setForeground(MUTED);
        stopHint.setFont(stopHint.getFont().deriveFont(11f));
        stopHint.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        details.add(stopHint);
        updateStepRows(0, 0, List.of(
            "1. Palmas", "2. Dorsos", "3. Interdigitales", "4. Nudillos",
            "5. Pulgar", "6. Punta de dedos"));
        main.add(details, BorderLayout.EAST);
        return main;
    }

    private JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(NAVY);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        return label;
    }

    private JLabel spaced(JLabel label) {
        label.setForeground(MUTED);
        label.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private void updateStepRows(int completed, int currentStepNumber, List<String> labels) {
        for (int index = 0; index < stepRows.length; index++) {
            if (index >= labels.size()) {
                stepRows[index].setVisible(false);
                continue;
            }
            stepRows[index].setVisible(true);
            boolean done = index < completed;
            boolean active = !done && index + 1 == currentStepNumber;
            String marker = done ? "✓" : active ? "▶" : "○";
            stepRows[index].setText(marker + "  " + labels.get(index));
            stepRows[index].setForeground(done || active ? TEAL : MUTED);
            stepRows[index].setFont(stepRows[index].getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
        }
    }

    private static String duration(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String seconds(long milliseconds) {
        return String.format("%.1f", Math.max(0, milliseconds) / 1000.0);
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? String.valueOf(id) : id.substring(0, 8);
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) return "sin datos";
        return value.replace("_", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static void runOnEdt(Runnable update) {
        if (SwingUtilities.isEventDispatchThread()) update.run();
        else SwingUtilities.invokeLater(update);
    }

    private static final class VideoPanel extends JPanel {
        private BufferedImage frame;
        private String placeholder = "Esperando la cámara de Continuidad…";

        private VideoPanel() {
            setBackground(new Color(14, 23, 31));
            setBorder(BorderFactory.createLineBorder(new Color(211, 221, 228)));
        }

        private void setFrame(BufferedImage next) {
            frame = next;
            placeholder = "";
            repaint();
        }

        private void setPlaceholder(String text) {
            frame = null;
            placeholder = text;
            repaint();
        }

        private boolean hasFrame() {
            return frame != null;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (frame == null) {
                    canvas.setColor(new Color(225, 235, 240));
                    canvas.setFont(getFont().deriveFont(15f));
                    canvas.drawString(placeholder, 24, getHeight() / 2);
                    return;
                }
                double scale = Math.min((double) getWidth() / frame.getWidth(),
                    (double) getHeight() / frame.getHeight());
                int width = (int) Math.round(frame.getWidth() * scale);
                int height = (int) Math.round(frame.getHeight() * scale);
                int x = (getWidth() - width) / 2;
                int y = (getHeight() - height) / 2;
                canvas.drawImage(frame, x, y, width, height, null);
            } finally {
                canvas.dispose();
            }
        }
    }
}
