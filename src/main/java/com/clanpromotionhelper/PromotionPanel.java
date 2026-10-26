package com.clanpromotionhelper;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.event.DocumentListener;
import lombok.Value;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * Sidebar list of every clan member with their promotion status, laid out like RuneLite's own panels:
 * a tab bar, a search box, flat buttons, overview tiles and dark cards.
 * Updates can arrive from any thread and hundreds of times during a fetch, so they are coalesced
 * into at most one rebuild every {@value #REBUILD_DELAY_MS} ms on the Swing thread.
 */
@Singleton
class PromotionPanel extends PluginPanel
{
	private static final int REBUILD_DELAY_MS = 1000;
	/** Rows drawn at once; the tabs and search box narrow a larger clan down */
	private static final int MAX_ROWS = 200;
	/** Colours and spacing in the style of RuneLite's other plugin panels (e.g. Flipping Utilities) */
	private static final Color PANEL_BACKGROUND = ColorScheme.DARK_GRAY_COLOR;
	private static final Color CARD_BACKGROUND = new Color(27, 27, 27);
	private static final Color CARD_BORDER = ColorScheme.DARKER_GRAY_COLOR.darker();
	private static final String[] TABS = {"Ready", "Later", "Failed", "All"};

	@Value
	static class Summary
	{
		int memberCount;
		int dueCount;
		int checkedCount;
		int failedCount;
		boolean wiseOldManEnabled;
		boolean fetching;
		/** One-line note about the last Wise Old Man group refresh, or null */
		String groupStatus;
	}

	private final JLabel readyValue = new JLabel("0");
	private final JLabel checkedValue = new JLabel("0/0");
	private final JLabel failedValue = new JLabel("0");
	private final JLabel membersValue = new JLabel("0");
	private final JLabel note = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel lastChecked = new JLabel(" ", SwingConstants.CENTER);
	private final Timer ageTimer;
	private final JButton refreshButton = new JButton("Refresh now");
	private final IconTextField search = new IconTextField();
	private final JPanel list = new ViewportWidthPanel();
	private final Timer rebuildTimer;
	private Runnable refreshAction;
	/** Set when new data arrived while the sidebar was closed */
	private boolean rebuildWhenShown;
	private String selectedTab = TABS[0];

	// Only touched on the Swing thread
	private List<PromotionRow> rows = Collections.emptyList();
	private Summary summary = new Summary(0, 0, 0, 0, false, false, null);
	private volatile List<PromotionRow> pendingRows = Collections.emptyList();
	private volatile Summary pendingSummary = summary;

	@Inject
	PromotionPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(PANEL_BACKGROUND);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(PANEL_BACKGROUND);
		header.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 10));

		// Tabs, underlined in orange like the other plugin panels
		MaterialTabGroup tabs = new MaterialTabGroup();
		tabs.setBackground(PANEL_BACKGROUND);
		tabs.setLayout(new GridLayout(1, 0, 2, 0));
		MaterialTab first = null;
		for (String name : TABS)
		{
			MaterialTab tab = new CompactTab(name, tabs);
			tab.setOnSelectEvent(() ->
			{
				selectedTab = name;
				rebuild();
				return true;
			});
			tab.setToolTipText("Later".equals(name) ? "Members who aren't ready for their next rank yet"
				: "Failed".equals(name) ? "Members whose Wise Old Man lookup failed (hover a card for the reason)" : null);
			tabs.addTab(tab);
			if (first == null)
			{
				first = tab;
			}
		}
		tabs.select(first);
		header.add(tabs);
		header.add(gap(8));

		search.setIcon(IconTextField.Icon.SEARCH);
		search.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
		search.setMinimumSize(new Dimension(0, 30));
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, CARD_BORDER));
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});
		search.addClearListener(this::rebuild);
		styleSearchBox(search);
		header.add(search);
		header.add(gap(8));

		styleButton(refreshButton);
		refreshButton.setToolTipText("Re-check XP now instead of waiting for the refresh interval");
		refreshButton.addActionListener(e ->
		{
			if (refreshAction != null)
			{
				refreshAction.run();
			}
		});
		header.add(refreshButton);
		header.add(gap(10));

		JPanel overview = new JPanel(new GridLayout(2, 2, 6, 6));
		overview.setOpaque(false);
		overview.add(tile("Ready", readyValue, ColorScheme.PROGRESS_COMPLETE_COLOR));
		overview.add(tile("XP checked", checkedValue, Color.WHITE));
		overview.add(tile("Failed", failedValue, ColorScheme.PROGRESS_ERROR_COLOR));
		overview.add(tile("Members", membersValue, Color.WHITE));
		header.add(overview);
		header.add(gap(6));

		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(note);

		// "Last checked" line, shown on the Ready tab only
		lastChecked.setFont(FontManager.getRunescapeSmallFont());
		lastChecked.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lastChecked.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		header.add(lastChecked);
		for (Component child : header.getComponents())
		{
			if (child instanceof JComponent)
			{
				((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
			}
		}
		note.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(header, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(PANEL_BACKGROUND);
		list.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 7));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setBackground(PANEL_BACKGROUND);
		scroll.getViewport().setBackground(PANEL_BACKGROUND);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setViewportBorder(null);
		scroll.getVerticalScrollBar().setPreferredSize(new Dimension(2, 0));
		scroll.getVerticalScrollBar().setBackground(PANEL_BACKGROUND);
		scroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll, BorderLayout.CENTER);

		rebuildTimer = new Timer(REBUILD_DELAY_MS, e ->
		{
			rows = pendingRows;
			summary = pendingSummary;
			// Drawing up to 200 cards is wasted effort while the sidebar is closed; catch up when it opens
			if (isShowing())
			{
				rebuild();
			}
			else
			{
				rebuildWhenShown = true;
			}
		});
		rebuildTimer.setRepeats(false);
		addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && rebuildWhenShown)
			{
				rebuildWhenShown = false;
				rebuild();
			}
		});

		// Keep the "5 min ago" text current while nothing else is updating
		ageTimer = new Timer(30_000, e -> updateLastChecked());
		ageTimer.start();
	}

	/** Swing thread only. */
	private void updateLastChecked()
	{
		boolean show = "Ready".equals(selectedTab);
		lastChecked.setVisible(show);
		if (!show)
		{
			return;
		}

		long newest = 0;
		long oldest = Long.MAX_VALUE;
		for (PromotionRow row : rows)
		{
			if (row.getCheckedAt() > 0)
			{
				newest = Math.max(newest, row.getCheckedAt());
				oldest = Math.min(oldest, row.getCheckedAt());
			}
		}

		long now = System.currentTimeMillis();
		if (newest == 0)
		{
			lastChecked.setText("Not checked yet");
			lastChecked.setToolTipText(null);
		}
		else
		{
			lastChecked.setText("Last checked " + TimeAgo.format(now, newest));
			lastChecked.setToolTipText("Oldest result: " + TimeAgo.format(now, oldest));
		}
	}

	/** A thin dark scroll bar: panel-coloured track, subtle thumb, no arrow buttons. */
	private static final class DarkScrollBarUI extends BasicScrollBarUI
	{
		@Override
		protected void configureScrollBarColors()
		{
			thumbColor = new Color(75, 75, 75);
			thumbDarkShadowColor = thumbColor;
			thumbHighlightColor = thumbColor;
			thumbLightShadowColor = thumbColor;
			trackColor = PANEL_BACKGROUND;
			trackHighlightColor = PANEL_BACKGROUND;
		}

		@Override
		protected JButton createDecreaseButton(int orientation)
		{
			return emptyButton();
		}

		@Override
		protected JButton createIncreaseButton(int orientation)
		{
			return emptyButton();
		}

		private static JButton emptyButton()
		{
			JButton button = new JButton();
			button.setPreferredSize(new Dimension(0, 0));
			button.setMinimumSize(new Dimension(0, 0));
			button.setMaximumSize(new Dimension(0, 0));
			return button;
		}

		@Override
		protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds)
		{
			g.setColor(PANEL_BACKGROUND);
			g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
		}

		@Override
		protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds)
		{
			g.setColor(thumbColor);
			g.fillRect(thumbBounds.x, thumbBounds.y, thumbBounds.width, thumbBounds.height);
		}
	}

	/** A panel that is exactly as wide as the scroll pane's viewport, so wide rows are cut off instead of overflowing. */
	private static final class ViewportWidthPanel extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/**
	 * A RuneLite tab with less side padding, so four tab names fit the sidebar's width.
	 * It keeps the orange underline on the selected tab.
	 */
	private static final class CompactTab extends MaterialTab
	{
		private static final Border SELECTED = BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(5, 2, 4, 2));
		private static final Border UNSELECTED = BorderFactory.createEmptyBorder(5, 2, 5, 2);

		CompactTab(String name, MaterialTabGroup group)
		{
			super(name, group, null);
			setFont(FontManager.getRunescapeSmallFont());
			setBorder(UNSELECTED);
		}

		@Override
		public boolean select()
		{
			boolean selected = super.select();
			setBorder(SELECTED);
			return selected;
		}

		@Override
		public void unselect()
		{
			super.unselect();
			setBorder(UNSELECTED);
		}
	}

	/** A vertical gap that is left-aligned like the rest, since a centre-aligned one would shift the others sideways. */
	private static Component gap(int height)
	{
		JComponent filler = (JComponent) Box.createVerticalStrut(height);
		filler.setAlignmentX(Component.LEFT_ALIGNMENT);
		return filler;
	}

	/**
	 * RuneLite's search box doesn't expose its inner parts, so reach in and give the typed text a light grey
	 * (the default is too close to the dark background) and make the clear button transparent so it shows
	 * the box's own background instead of a white square.
	 */
	private static void styleSearchBox(Container box)
	{
		for (Component child : box.getComponents())
		{
			if (child instanceof FlatTextField)
			{
				JTextField field = ((FlatTextField) child).getTextField();
				field.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				field.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
			}
			else if (child instanceof JButton)
			{
				JButton button = (JButton) child;
				button.setOpaque(false);
				button.setContentAreaFilled(false);
				button.setBorderPainted(false);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}

			if (child instanceof Container)
			{
				styleSearchBox((Container) child);
			}
		}
	}

	/** A flat dark button, like the Update and Open Profile buttons in other panels. */
	private static void styleButton(JButton button)
	{
		button.setFocusPainted(false);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setForeground(Color.WHITE);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, CARD_BORDER),
			BorderFactory.createEmptyBorder(6, 10, 6, 10)));
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		button.setPreferredSize(new Dimension(10, 32));
	}

	/** One overview tile: a small caption over a coloured value, on a dark card. */
	private static JPanel tile(String caption, JLabel value, Color valueColor)
	{
		JPanel tile = new JPanel(new BorderLayout());
		tile.setBackground(CARD_BACKGROUND);
		tile.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(2, 2, 2, 2, CARD_BORDER),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));

		JLabel label = new JLabel(caption);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		value.setFont(FontManager.getRunescapeBoldFont());
		value.setForeground(valueColor);

		tile.add(label, BorderLayout.NORTH);
		tile.add(value, BorderLayout.CENTER);
		return tile;
	}

	/** Called on the Swing thread when the Refresh button is clicked; it must not block. */
	void setRefreshAction(Runnable action)
	{
		refreshAction = action;
	}

	/** Safe to call from any thread. */
	void update(List<PromotionRow> newRows, Summary newSummary)
	{
		pendingRows = newRows;
		pendingSummary = newSummary;
		SwingUtilities.invokeLater(() ->
		{
			if (!rebuildTimer.isRunning())
			{
				rebuildTimer.start();
			}
		});
	}

	void stop()
	{
		SwingUtilities.invokeLater(() ->
		{
			rebuildTimer.stop();
			ageTimer.stop();
		});
	}

	/** Swing thread only. */
	private void rebuild()
	{
		readyValue.setText(String.valueOf(summary.getDueCount()));
		checkedValue.setText(summary.getCheckedCount() + "/" + summary.getMemberCount());
		failedValue.setText(String.valueOf(summary.getFailedCount()));
		membersValue.setText(String.valueOf(summary.getMemberCount()));

		if (summary.getMemberCount() == 0)
		{
			note.setText("Log in and open your clan to load members");
		}
		else if (!summary.isWiseOldManEnabled())
		{
			note.setText("Turn on Wise Old Man lookups in the settings");
		}
		else
		{
			note.setText(summary.getGroupStatus() == null ? " " : summary.getGroupStatus());
			note.setToolTipText(summary.getGroupStatus());
		}
		updateLastChecked();
		refreshButton.setEnabled(summary.isWiseOldManEnabled() && !summary.isFetching() && summary.getMemberCount() > 0);

		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		List<PromotionRow> shown = new ArrayList<>();
		for (PromotionRow row : rows)
		{
			if (matchesTab(row, selectedTab) && (query.isEmpty() || row.getName().toLowerCase(Locale.ROOT).contains(query)))
			{
				shown.add(row);
			}
		}
		shown.sort(Comparator.comparing((PromotionRow row) -> row.getStatus().ordinal())
			.thenComparing(PromotionRow::getName, String.CASE_INSENSITIVE_ORDER));

		list.removeAll();
		int count = Math.min(MAX_ROWS, shown.size());
		for (int i = 0; i < count; i++)
		{
			list.add(buildRow(shown.get(i)));
			list.add(gap(6));
		}
		if (shown.isEmpty())
		{
			JLabel empty = new JLabel(rows.isEmpty() ? "No clan members yet" : "Nobody here", SwingConstants.CENTER);
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setMaximumSize(new Dimension(Integer.MAX_VALUE, empty.getPreferredSize().height + 14));
			empty.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
			list.add(empty);
		}
		if (shown.size() > count)
		{
			JLabel more = new JLabel("+" + (shown.size() - count) + " more: use search", SwingConstants.CENTER);
			more.setFont(FontManager.getRunescapeSmallFont());
			more.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			more.setMaximumSize(new Dimension(Integer.MAX_VALUE, more.getPreferredSize().height));
			list.add(more);
		}
		// A box layout shifts every row if any one of them is aligned differently (the "more" and
		// "nobody here" labels used to be centred), so force them all to the same alignment.
		for (Component child : list.getComponents())
		{
			if (child instanceof JComponent)
			{
				((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
			}
		}
		list.revalidate();
		list.repaint();
	}

	private static boolean matchesTab(PromotionRow row, String tab)
	{
		switch (tab)
		{
			case "Ready":
				return row.getStatus() == PromotionStatus.READY;
			case "Later":
				return row.getStatus() == PromotionStatus.NOT_READY;
			case "Failed":
				return row.getStatus() == PromotionStatus.FAILED;
			default:
				return true;
		}
	}

	/** A dark card with the member's name and status, their ranks and months, and progress bars. */
	private static JPanel buildRow(PromotionRow row)
	{
		Color accent = statusColor(row.getStatus());

		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBackground(CARD_BACKGROUND);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(2, 2, 2, 2, CARD_BORDER),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		// The member's clan rank icon from the game, or a coloured status dot until it has loaded
		Icon icon = row.getRankIcon() != null ? new ImageIcon(row.getRankIcon()) : new StatusIcon(row.getStatus(), accent);
		JLabel name = new JLabel(row.getName(), icon, JLabel.LEFT);
		name.setIconTextGap(6);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);

		JLabel status = new JLabel(statusText(row));
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(accent);

		boolean hasBars = row.getRequiredMonths() > 0 || row.getRequiredXpMillions() > 0;
		JLabel details = new JLabel(detailsText(row, !hasBars));
		details.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN));
		details.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(name, BorderLayout.WEST);
		top.add(status, BorderLayout.EAST);

		JPanel south = new JPanel();
		south.setOpaque(false);
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		details.setAlignmentX(Component.LEFT_ALIGNMENT);
		south.add(details);
		if (hasBars)
		{
			double monthFraction = row.getRequiredMonths() == 0 ? 1 : (double) row.getMonths() / row.getRequiredMonths();
			Double xpFraction = row.getXpGained() == null ? null
				: row.getRequiredXpMillions() == 0 ? Double.valueOf(1)
				: Double.valueOf(row.getXpGained() / 1_000_000.0 / row.getRequiredXpMillions());
			south.add(gap(2));
			south.add(new MiniBar(row.getMonths() + " mo", monthFraction));
			south.add(new MiniBar(row.getXpGained() == null ? "--" : formatXp(row.getXpGained()), xpFraction));
		}

		panel.add(top, BorderLayout.NORTH);
		panel.add(south, BorderLayout.SOUTH);
		if (row.getDetail() != null)
		{
			panel.setToolTipText(row.getDetail());
		}
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/** A small round icon in the status colour, with a glyph drawn in code so it needs no image files. */
	private static final class StatusIcon implements Icon
	{
		private static final int SIZE = 12;
		private final PromotionStatus status;
		private final Color color;

		StatusIcon(PromotionStatus status, Color color)
		{
			this.status = status;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(x, y, SIZE, SIZE);
			g2.setColor(CARD_BACKGROUND);
			g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int cx = x + SIZE / 2;
			int cy = y + SIZE / 2;
			switch (status)
			{
				case READY:
					g2.drawLine(cx - 3, cy + 1, cx, cy - 2);
					g2.drawLine(cx, cy - 2, cx + 3, cy + 1);
					break;
				case FAILED:
					g2.drawLine(cx, cy - 3, cx, cy + 0);
					g2.drawLine(cx, cy + 2, cx, cy + 2);
					break;
				case NOT_READY:
					g2.drawLine(cx - 3, cy, cx + 3, cy);
					break;
				default:
					g2.drawLine(cx - 3, cy, cx - 3, cy);
					g2.drawLine(cx, cy, cx, cy);
					g2.drawLine(cx + 3, cy, cx + 3, cy);
					break;
			}
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return SIZE;
		}

		@Override
		public int getIconHeight()
		{
			return SIZE;
		}
	}

	/** A labelled thin progress bar: green once the requirement is met, amber while it isn't, empty with no data. */
	private static final class MiniBar extends JComponent
	{
		/** Tall enough for the value text; the bar itself is thinner and centred in it */
		private static final int HEIGHT = 14;
		private static final int BAR_HEIGHT = 7;
		private static final int LABEL_WIDTH = 38;
		private final String label;
		private final Double fraction;

		MiniBar(String label, Double fraction)
		{
			this.label = label;
			this.fraction = fraction;
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setPreferredSize(new Dimension(10, HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
			setMinimumSize(new Dimension(10, HEIGHT));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			java.awt.FontMetrics metrics = g2.getFontMetrics();
			int baseline = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
			g2.drawString(label, 0, baseline);

			int barX = LABEL_WIDTH;
			int barY = (getHeight() - BAR_HEIGHT) / 2;
			int barWidth = Math.max(1, getWidth() - barX);
			g2.setColor(CARD_BORDER);
			g2.fillRoundRect(barX, barY, barWidth, BAR_HEIGHT, 4, 4);
			if (fraction != null)
			{
				double clamped = Math.max(0, Math.min(1, fraction));
				g2.setColor(fraction >= 1 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
				g2.fillRoundRect(barX, barY, (int) Math.round(barWidth * clamped), BAR_HEIGHT, 4, 4);
			}
			g2.dispose();
		}
	}

	static String statusText(PromotionRow row)
	{
		switch (row.getStatus())
		{
			case PENDING:
				return "Waiting";
			case FAILED:
				return "Failed";
			case UNKNOWN_RANK:
				return "No rank";
			case MAX_RANK:
				return "Top";
			default:
				return row.getStatus().getLabel();
		}
	}

	/** The rank line; the months and XP are added only when the card has no progress bars to show them. */
	static String detailsText(PromotionRow row, boolean includeStats)
	{
		StringBuilder text = new StringBuilder(row.getCurrentRank() == null ? "Unknown rank" : row.getCurrentRank());
		// The RuneScape font has no arrow glyph, so use a plain ">"
		if (row.getNextRank() != null)
		{
			text.append(" > ").append(row.getNextRank());
		}
		if (includeStats)
		{
			text.append(" \u00B7 ").append(row.getMonths()).append(" mo");
			if (row.getXpGained() != null)
			{
				text.append(" \u00B7 ").append(formatXp(row.getXpGained()));
			}
		}
		return text.toString();
	}

	static String formatXp(long xp)
	{
		return String.format(Locale.ENGLISH, "%.1fM", xp / 1_000_000.0);
	}

	private static Color statusColor(PromotionStatus status)
	{
		switch (status)
		{
			case READY:
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			case FAILED:
				return ColorScheme.PROGRESS_ERROR_COLOR;
			case NOT_READY:
				return ColorScheme.PROGRESS_INPROGRESS_COLOR;
			case IGNORED:
				return ColorScheme.BRAND_ORANGE;
			default:
				return ColorScheme.MEDIUM_GRAY_COLOR;
		}
	}
}
