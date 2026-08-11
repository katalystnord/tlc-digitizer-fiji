package se.katalystnord.tlcdigitizer.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;

/**
 * Shared visual vocabulary for the wizard: colours, type scale, spacing, and the
 * few custom-painted components the step panels reuse.
 *
 * <p><b>Why this exists.</b> Every step panel previously derived its own fonts and
 * picked its own greys inline, so nothing lined up: three different "small grey
 * caption" treatments, two different section-header sizes, and a step indicator
 * whose active colour matched nothing else in the product. Screenshots made the
 * inconsistency obvious in a way that using the wizard did not.
 *
 * <p><b>Deliberately conservative about the look-and-feel.</b> Fiji does not
 * guarantee which Swing LAF is installed, and hard-coding component colours would
 * break badly under a dark LAF. So this class only fixes the things that carry
 * meaning — the accent, the badge colours, the type scale — and derives everything
 * else ({@link #ink()}, {@link #line()}) from the active LAF, falling back to
 * sensible constants when the LAF does not define them.
 */
final class Ui {

    private Ui() {}

    // -----------------------------------------------------------------------
    // Colour
    // -----------------------------------------------------------------------

    /**
     * Product accent. Matches the accent used on the project's landing page, so the
     * plugin and the site read as one product rather than two unrelated artefacts.
     */
    static final Color ACCENT = new Color(0x1F6E5C);
    static final Color ACCENT_TINT = new Color(0xE5F2EE);

    /** Beta/advisory colour — amber rather than red: these features work, they are just unvalidated. */
    static final Color AMBER = new Color(0xA97318);
    static final Color AMBER_TINT = new Color(0xFAF1E0);

    /** Primary text colour, from the LAF so a dark theme still reads correctly. */
    static Color ink() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : new Color(0x1C1F1E);
    }

    /** Secondary text — captions, units, supporting detail. */
    static Color inkSoft() {
        Color base = ink();
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return new Color(0x6B726E);
        // Blend halfway toward the background so this stays legible in light and dark themes.
        return new Color(
            (base.getRed()   + bg.getRed())   / 2,
            (base.getGreen() + bg.getGreen()) / 2,
            (base.getBlue()  + bg.getBlue())  / 2);
    }

    /** Hairline separator colour. */
    static Color line() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(0xD8D8D8);
    }

    // -----------------------------------------------------------------------
    // Type
    // -----------------------------------------------------------------------

    private static Font base() {
        Font f = UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    static Font body()          { return base().deriveFont(Font.PLAIN, 12f); }
    static Font bodyBold()      { return base().deriveFont(Font.BOLD,  12f); }
    static Font sectionHeader() { return base().deriveFont(Font.BOLD,  13f); }
    static Font caption()       { return base().deriveFont(Font.PLAIN, 11f); }
    static Font badge()         { return base().deriveFont(Font.BOLD,  10f); }

    // -----------------------------------------------------------------------
    // Spacing
    // -----------------------------------------------------------------------

    /** Gap between a control and its own description. */
    static final int GAP_TIGHT = 4;
    /** Gap between sibling controls in a list. */
    static final int GAP = 8;
    /** Gap between distinct sections. */
    static final int GAP_SECTION = 16;

    /** Width used for wrapped HTML labels. Swing will not wrap without an explicit width. */
    static final int TEXT_WIDTH = 470;

    /**
     * Wraps text in HTML at a fixed width. Swing labels do not wrap otherwise, and an
     * unwrapped label silently widens the whole wizard: {@code CardLayout} sizes the window
     * to the widest of all seven panels, computed once at {@code pack()} time, so one long
     * label makes every step wider.
     */
    static String wrap(String html) {
        return "<html><body style='width:" + TEXT_WIDTH + "px'>" + html + "</body></html>";
    }

    /** A muted caption label, wrapped and indented to sit under a control. */
    static JLabel caption(String html, int indent) {
        JLabel l = new JLabel(wrap(html));
        l.setFont(caption());
        l.setForeground(inkSoft());
        l.setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** A hairline divider with vertical breathing room. */
    static Border dividerAbove(int padTop) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, line()),
            BorderFactory.createEmptyBorder(padTop, 0, 0, 0));
    }

    // -----------------------------------------------------------------------
    // Badge
    // -----------------------------------------------------------------------

    /**
     * A small pill label — "Validated" / "Beta".
     *
     * <p>Replaces the inline parenthetical it used to take three lines of grey text to say.
     * The parentheticals were accurate but dominated the panel: the largest block of text on
     * the busiest step read "not yet validated against the reference plates" three times over.
     * A badge carries the same warning at a glance without crowding out what each method does.
     */
    static JLabel pill(String text, Color fg, Color bg) {
        JLabel l = new JLabel(text.toUpperCase(), SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 60));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(badge());
        l.setForeground(fg);
        l.setOpaque(false);
        l.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        Dimension d = l.getPreferredSize();
        l.setMaximumSize(d);
        l.setMinimumSize(d);
        return l;
    }

    static JLabel validatedBadge() { return pill("Validated", ACCENT, ACCENT_TINT); }
    static JLabel betaBadge()      { return pill("Beta", AMBER, AMBER_TINT); }

    // -----------------------------------------------------------------------
    // Step indicator chip
    // -----------------------------------------------------------------------

    /** The three states a step can be in, which the old flat label bar did not distinguish. */
    enum StepState { DONE, ACTIVE, UPCOMING }

    /**
     * One step in the indicator bar, painted as a rounded chip.
     *
     * <p>The previous bar drew every step as a plain label and filled the active one with a
     * solid blue rectangle, which read as a selected table cell rather than progress: there
     * was no way to tell a completed step from one not yet reached.
     */
    static final class StepChip extends JLabel {
        private StepState state = StepState.UPCOMING;

        StepChip(String text) {
            super(text, SwingConstants.CENTER);
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            setOpaque(false);
        }

        void setState(StepState s) {
            this.state = s;
            setFont(base().deriveFont(s == StepState.ACTIVE ? Font.BOLD : Font.PLAIN, 11f));
            setForeground(s == StepState.ACTIVE ? Color.WHITE
                        : s == StepState.DONE   ? ACCENT
                        : inkSoft());
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Insets in = new Insets(3, 2, 3, 2);
            int w = getWidth() - in.left - in.right;
            int h = getHeight() - in.top - in.bottom;
            int arc = h;
            if (state == StepState.ACTIVE) {
                g2.setColor(ACCENT);
                g2.fillRoundRect(in.left, in.top, w, h, arc, arc);
            } else if (state == StepState.DONE) {
                g2.setColor(ACCENT_TINT);
                g2.fillRoundRect(in.left, in.top, w, h, arc, arc);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
