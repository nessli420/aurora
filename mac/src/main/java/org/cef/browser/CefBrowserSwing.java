package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.dnd.DropTarget;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

/**
 * An off-screen browser that draws cef's frames with java2d instead of jogl.
 *
 * <p>jcef's own off-screen browser paints through a jogl canvas, and jogl 2.4 closes appkit windows off the
 * main thread, which current macos answers by killing the process. cef already hands every frame over as
 * pixels, so this copies them into an image that swing draws; input reaches cef the same way jcef's browser
 * sends it. It lives in jcef's package because the browser base class is package-private.
 */
public final class CefBrowserSwing extends CefBrowser_N implements CefRenderHandler {
    private final boolean transparent;
    private final Object lock = new Object();
    private BufferedImage frame;
    private BufferedImage popupFrame;
    private Rectangle popupRect;
    // cef issue #1437 wants a non-empty view before the first resize
    private final Rectangle viewRect = new Rectangle(0, 0, 1, 1);
    private volatile double scale = 1.0;
    private boolean justCreated;
    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> paintListeners = new CopyOnWriteArrayList<>();
    private final JPanel panel;

    public CefBrowserSwing(CefClient client, String url) {
        this(client, url, false, null, null, null, null);
    }

    private CefBrowserSwing(CefClient client, String url, boolean transparent, CefRequestContext context,
            CefBrowserSwing parent, Point inspectAt, CefBrowserSettings settings) {
        super(client, url, context, parent, inspectAt, settings);
        this.transparent = transparent;
        this.panel = new View();
        panel.setFocusable(true);
        // tab moves between the page's own fields, so swing must not take it for focus traversal
        panel.setFocusTraversalKeysEnabled(false);
        panel.setBackground(Color.WHITE);
        panel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { resized(); }
            @Override public void componentShown(ComponentEvent e) { resized(); }
        });
        panel.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing()) resized();
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                panel.requestFocusInWindow();
                sendMouseEvent(e);
            }
            @Override public void mouseReleased(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseEntered(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseExited(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseClicked(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseMoved(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseDragged(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { sendMouseWheelEvent(e); }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(mouse);
        panel.addMouseWheelListener(mouse);
        panel.addKeyListener(new KeyListener() {
            @Override public void keyTyped(KeyEvent e) { sendKeyEvent(e); }
            @Override public void keyPressed(KeyEvent e) { sendKeyEvent(e); }
            @Override public void keyReleased(KeyEvent e) { sendKeyEvent(e); }
        });
        panel.addFocusListener(new FocusListener() {
            @Override public void focusGained(FocusEvent e) {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                setFocus(true);
            }
            @Override public void focusLost(FocusEvent e) { setFocus(false); }
        });
        new DropTarget(panel, new CefDropTargetListener(this));
    }

    private final class View extends JPanel {
        private boolean removed = true;

        View() {
            super(null, false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            createIfRequired(true);
            readScale(g);
            if (!transparent) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            synchronized (lock) {
                if (frame == null) return;
                // the image is in device pixels; drawing it at the view's size lets the retina transform map it 1:1
                g2.drawImage(frame, 0, 0, (int) Math.round(frame.getWidth() / scale), (int) Math.round(frame.getHeight() / scale), null);
                if (popupFrame != null && popupRect != null) {
                    g2.drawImage(popupFrame, popupRect.x, popupRect.y, popupRect.width, popupRect.height, null);
                }
            }
        }

        @Override
        public void addNotify() {
            super.addNotify();
            if (removed) {
                getClient().onAfterParentChanged(CefBrowserSwing.this);
                removed = false;
            }
        }

        @Override
        public void removeNotify() {
            if (!removed) {
                if (!isClosed()) getClient().onAfterParentChanged(CefBrowserSwing.this);
                removed = true;
            }
            super.removeNotify();
        }
    }

    private void readScale(Graphics g) {
        if (!(g instanceof Graphics2D)) return;
        double now = ((Graphics2D) g).getTransform().getScaleX();
        if (now > 0 && now != scale) {
            scale = now;
            // a resize makes cef ask for the screen info again, which carries the new scale
            wasResized(Math.max(1, panel.getWidth()), Math.max(1, panel.getHeight()));
        }
    }

    private void resized() {
        int width = Math.max(1, panel.getWidth());
        int height = Math.max(1, panel.getHeight());
        GraphicsConfiguration config = panel.getGraphicsConfiguration();
        if (config != null && config.getDefaultTransform().getScaleX() > 0) scale = config.getDefaultTransform().getScaleX();
        synchronized (lock) {
            viewRect.setBounds(0, 0, width, height);
        }
        createIfRequired(panel.isDisplayable());
        wasResized(width, height);
    }

    private void createIfRequired(boolean hasParent) {
        if (getNativeRef("CefBrowser") == 0) {
            // an off-screen browser needs no parent window, so the handle stays 0
            if (getParentBrowser() != null) {
                createDevTools(getParentBrowser(), getClient(), 0, true, transparent, null, getInspectAt());
            } else {
                createBrowser(getClient(), 0, getUrl(), true, transparent, null, getRequestContext());
            }
        } else if (hasParent && justCreated) {
            getClient().onAfterParentChanged(this);
            setFocus(true);
            justCreated = false;
        }
    }

    @Override
    public void createImmediately() {
        justCreated = true;
        createIfRequired(false);
    }

    @Override
    public Component getUIComponent() {
        return panel;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context,
            CefBrowser_N parent, Point inspectAt) {
        return new CefBrowserSwing(client, url, transparent, context, this, inspectAt, null);
    }

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        synchronized (lock) {
            return new Rectangle(viewRect);
        }
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        Point origin = new Point(0, 0);
        try {
            if (panel.isShowing()) origin = panel.getLocationOnScreen();
        } catch (RuntimeException ignored) {
            // the window went away between the check and the call
        }
        return new Point(origin.x + viewPoint.x, origin.y + viewPoint.y);
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        Rectangle bounds = getViewRect(browser);
        screenInfo.Set(scale, 32, 8, false, bounds, bounds);
        return true;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        if (!show) {
            synchronized (lock) {
                popupFrame = null;
                popupRect = null;
            }
            panel.repaint();
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        synchronized (lock) {
            popupRect = new Rectangle(size);
        }
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (width <= 0 || height <= 0) return;
        synchronized (lock) {
            BufferedImage current = popup ? popupFrame : frame;
            BufferedImage image = current != null && current.getWidth() == width && current.getHeight() == height
                    ? current
                    : new BufferedImage(width, height, transparent ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
            int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            // cef's bgra bytes read as little-endian ints are exactly java's argb
            IntBuffer ints = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            if (image != current || dirtyRects == null || dirtyRects.length == 0) {
                ints.position(0);
                ints.get(pixels, 0, Math.min(pixels.length, ints.remaining()));
            } else {
                Rectangle all = new Rectangle(0, 0, width, height);
                for (Rectangle dirty : dirtyRects) {
                    Rectangle r = dirty.intersection(all);
                    for (int y = r.y; y < r.y + r.height; y++) {
                        int at = y * width + r.x;
                        ints.position(at);
                        ints.get(pixels, at, r.width);
                    }
                }
            }
            if (popup) popupFrame = image; else frame = image;
        }
        panel.repaint();
        if (!paintListeners.isEmpty()) {
            CefPaintEvent event = new CefPaintEvent(browser, popup, dirtyRects, buffer, width, height);
            for (Consumer<CefPaintEvent> listener : paintListeners) listener.accept(event);
        }
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        SwingUtilities.invokeLater(() -> panel.setCursor(new Cursor(cursorType)));
        return true;
    }

    // dragging out of a sign-in page is never needed; refusing lets cef cancel the drag cleanly
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {}

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
        paintListeners.add(listener);
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
        paintListeners.clear();
        paintListeners.add(listener);
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
        paintListeners.remove(listener);
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        synchronized (lock) {
            if (frame == null) return CompletableFuture.failedFuture(new IllegalStateException("Nothing painted yet"));
            int width = nativeResolution ? frame.getWidth() : (int) Math.ceil(frame.getWidth() / scale);
            int height = nativeResolution ? frame.getHeight() : (int) Math.ceil(frame.getHeight() / scale);
            BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = copy.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(frame, 0, 0, width, height, null);
            g.dispose();
            return CompletableFuture.completedFuture(copy);
        }
    }
}
