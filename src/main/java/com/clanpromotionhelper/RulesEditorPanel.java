package com.clanpromotionhelper;

import static com.clanpromotionhelper.PromotionPanel.CARD_BACKGROUND;
import static com.clanpromotionhelper.PromotionPanel.CARD_BORDER;
import static com.clanpromotionhelper.PromotionPanel.PANEL_BACKGROUND;
import static com.clanpromotionhelper.PromotionPanel.gap;
import static com.clanpromotionhelper.PromotionPanel.styleButton;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.Border;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Sidebar editor for the rank ladder, based on the one ItsMajorSpace contributed in pull request #1. It reads and writes the
 * same text setting as the plugin's configuration panel, so either can be used, and checks every rank before
 * saving anything.
 */
@Singleton
class RulesEditorPanel extends JPanel
{
	private static final Border FIELD_BORDER = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(1, 1, 1, 1, CARD_BORDER), BorderFactory.createEmptyBorder(2, 4, 2, 4));
	private static final Border CARD = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(2, 2, 2, 2, CARD_BORDER), BorderFactory.createEmptyBorder(6, 8, 6, 8));
	private static final Border CARD_ERROR = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(2, 2, 2, 2, ColorScheme.PROGRESS_ERROR_COLOR), BorderFactory.createEmptyBorder(6, 8, 6, 8));

	private final ConfigManager configManager;
	private final ClanPromotionHelperConfig config;
	private final JPanel ladderList = column();
	private final JLabel status = new JLabel(" ");
	private final List<LadderCard> ladder = new ArrayList<>();
	private Supplier<List<String>> clanRanks = Collections::emptyList;
	private Runnable onClose = () -> { };

	@Inject
	RulesEditorPanel(ConfigManager configManager, ClanPromotionHelperConfig config)
	{
		this.configManager = configManager;
		this.config = config;
		setLayout(new BorderLayout());
		setBackground(PANEL_BACKGROUND);

		JPanel content = new PromotionPanel.ViewportWidthPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(PANEL_BACKGROUND);
		content.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 7));
		content.add(heading("Rank ladder", "Lowest first. A member can move up one rank at a time. "
			+ "Leave a box at 0 to not require it."));
		content.add(ladderList);
		JPanel ladderButtons = new JPanel(new GridLayout(1, 2, 6, 0));
		ladderButtons.setOpaque(false);
		ladderButtons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		ladderButtons.add(addButton("Add rank", () -> addLadder(new RankRules.Rank("", 0, 0))));
		JButton fill = addButton("Fill from clan", this::fillFromClan);
		fill.setToolTipText("List your clan's ranks in their in-game order, keeping the requirements already set for them");
		ladderButtons.add(fill);
		content.add(ladderButtons);
		leftAlign(content);
		add(PromotionPanel.darkScroll(content), BorderLayout.CENTER);

		JButton save = new JButton("Save");
		JButton cancel = new JButton("Cancel");
		styleButton(save);
		styleButton(cancel);
		save.addActionListener(e -> save());
		cancel.addActionListener(e -> onClose.run());
		JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setOpaque(false);
		buttons.add(save);
		buttons.add(cancel);

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		JPanel footer = new JPanel(new BorderLayout(0, 4));
		footer.setBackground(PANEL_BACKGROUND);
		footer.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 10));
		footer.add(status, BorderLayout.NORTH);
		footer.add(buttons, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);
	}

	/** Where the clan's rank titles come from, lowest first; read whenever Fill from clan is pressed. */
	void setClanRanks(Supplier<List<String>> clanRanks)
	{
		this.clanRanks = clanRanks;
	}

	void setOnClose(Runnable onClose)
	{
		this.onClose = onClose;
	}

	/** Reloads the ladder from the settings, dropping any unsaved edits. Swing thread only. */
	void open()
	{
		RankRules ranks = RankRules.parse(config.rankRequirements());
		ladder.clear();
		ladderList.removeAll();
		for (RankRules.Rank rank : ranks.getRanks())
		{
			addLadder(rank);
		}

		// Saving rewrites the setting from the cards, so lines the editor couldn't load would be lost
		int unreadable = ranks.getErrors().size();
		setStatus(unreadable == 0 ? " " : unreadable + " unreadable line" + (unreadable == 1 ? "" : "s") + " will be removed on save",
			unreadable == 0 ? null : String.join("\n", ranks.getErrors()));
		refresh();
	}

	private void save()
	{
		List<String> problems = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < ladder.size(); i++)
		{
			LadderCard card = ladder.get(i);
			String line = card.toLine();
			String name = card.name.getText().trim();
			String problem = name.isEmpty() ? "needs a name"
				: name.contains(":") ? "name can't contain \":\""
				: RankRules.parse(line).getErrors().isEmpty() ? null : "check the numbers";
			card.mark(problem);
			if (problem != null)
			{
				problems.add("Rank " + (i + 1) + ": " + problem);
			}
			lines.add(line);
		}

		if (!problems.isEmpty())
		{
			setStatus(problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + " (hover)", String.join("\n", problems));
			refresh();
			return;
		}

		configManager.setConfiguration(ClanPromotionHelperConfig.GROUP, "rankRequirements", String.join("\n", lines));
		setStatus(" ", null);
		onClose.run();
	}

	/**
	 * Replaces the ladder with the clan's in-game ranks, lowest first, keeping each existing card's requirements.
	 * Nothing is saved until Save, so Cancel undoes it.
	 */
	void fillFromClan()
	{
		List<String> titles = clanRanks.get();
		if (titles.isEmpty())
		{
			setStatus("Log in and open your clan to load its ranks", null);
			return;
		}

		Map<String, LadderCard> existing = new LinkedHashMap<>();
		for (LadderCard card : ladder)
		{
			existing.putIfAbsent(card.name.getText().trim().toLowerCase(Locale.ROOT), card);
		}

		List<LadderCard> filled = new ArrayList<>();
		for (String title : titles)
		{
			LadderCard card = existing.remove(title.toLowerCase(Locale.ROOT));
			if (card == null)
			{
				card = new LadderCard(new RankRules.Rank(title, 0, 0));
			}
			card.name.setText(title);
			filled.add(card);
		}
		ladder.clear();
		ladder.addAll(filled);
		relist();

		List<String> dropped = new ArrayList<>();
		for (LadderCard card : existing.values())
		{
			if (!card.name.getText().trim().isEmpty())
			{
				dropped.add(card.name.getText().trim());
			}
		}
		if (dropped.isEmpty())
		{
			setStatus("Ranks filled in: check them, then Save", null);
			status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else
		{
			setStatus("Removed " + dropped.size() + " rank" + (dropped.size() == 1 ? "" : "s") + " not in the clan (hover)",
				String.join("\n", dropped));
		}
	}

	private void setStatus(String text, String tooltip)
	{
		status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		status.setText(text);
		status.setToolTipText(tooltip == null ? null : "<html>" + escape(tooltip).replace("\n", "<br>") + "</html>");
	}

	private void addLadder(RankRules.Rank rank)
	{
		LadderCard card = new LadderCard(rank);
		ladder.add(card);
		ladderList.add(card);
		ladderList.add(gap(6));
		refresh();
	}

	private void moveLadder(LadderCard card, int by)
	{
		int from = ladder.indexOf(card);
		int to = from + by;
		if (to < 0 || to >= ladder.size())
		{
			return;
		}
		Collections.swap(ladder, from, to);
		relist();
	}

	private void relist()
	{
		ladderList.removeAll();
		for (LadderCard card : ladder)
		{
			ladderList.add(card);
			ladderList.add(gap(6));
		}
		refresh();
	}

	private void refresh()
	{
		leftAlign(this);
		revalidate();
		repaint();
	}

	/** One rung of the ladder: name and order on top, then months and XP. */
	private final class LadderCard extends JPanel
	{
		private final JTextField name = field(20);
		private final JTextField months = field(3);
		private final JTextField xp = field(4);

		LadderCard(RankRules.Rank rank)
		{
			super(new BorderLayout(0, 4));
			setBackground(CARD_BACKGROUND);
			setBorder(CARD);
			name.setText(rank.getName());
			months.setText(String.valueOf(rank.getMonths()));
			xp.setText(BigDecimal.valueOf(rank.getXpMillions()).stripTrailingZeros().toPlainString());

			JPanel top = new JPanel(new BorderLayout(4, 0));
			top.setOpaque(false);
			top.add(name, BorderLayout.CENTER);
			top.add(iconButtons(
				iconButton(Glyph.UP, "Move up", () -> moveLadder(this, -1)),
				iconButton(Glyph.DOWN, "Move down", () -> moveLadder(this, 1)),
				iconButton(Glyph.CROSS, "Remove", () ->
				{
					ladder.remove(this);
					relist();
				})), BorderLayout.EAST);

			JPanel numbers = new JPanel(new GridLayout(1, 2, 4, 0));
			numbers.setOpaque(false);
			numbers.add(labelled("Months", months));
			numbers.add(labelled("XP (millions)", xp));

			add(top, BorderLayout.NORTH);
			add(numbers, BorderLayout.CENTER);
		}

		String toLine()
		{
			return name.getText().trim() + ":" + months.getText().trim() + ":" + xp.getText().trim();
		}

		void mark(String problem)
		{
			setBorder(problem == null ? CARD : CARD_ERROR);
			setToolTipText(problem);
		}
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		return panel;
	}

	private static JPanel heading(String title, String description)
	{
		JPanel panel = new JPanel(new BorderLayout(0, 2));
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(Color.WHITE);
		// html with a fixed width so the description wraps inside the sidebar instead of being cut off
		JLabel descriptionLabel = new JLabel("<html><div style='width:150px'>" + escape(description) + "</div></html>");
		descriptionLabel.setFont(FontManager.getRunescapeSmallFont());
		descriptionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(titleLabel, BorderLayout.NORTH);
		panel.add(descriptionLabel, BorderLayout.CENTER);
		return panel;
	}

	private static JButton addButton(String text, Runnable action)
	{
		JButton button = new JButton(text);
		styleButton(button);
		button.addActionListener(e -> action.run());
		return button;
	}

	private static JButton iconButton(Glyph glyph, String tooltip, Runnable action)
	{
		// Drawn rather than typed: RuneLite's fonts, which buttons use by default, have no arrow or cross characters
		JButton button = new JButton(new GlyphIcon(glyph, ColorScheme.LIGHT_GRAY_COLOR));
		button.setDisabledIcon(new GlyphIcon(glyph, ColorScheme.MEDIUM_GRAY_COLOR));
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, CARD_BORDER));
		button.setPreferredSize(new Dimension(20, 20));
		button.addActionListener(e -> action.run());
		return button;
	}

	private enum Glyph
	{
		UP, DOWN, CROSS
	}

	/** A small up arrow, down arrow or cross, drawn with lines so it looks the same in any font. */
	private static final class GlyphIcon implements Icon
	{
		private static final int SIZE = 8;
		private final Glyph glyph;
		private final Color color;

		GlyphIcon(Glyph glyph, Color color)
		{
			this.glyph = glyph;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			switch (glyph)
			{
				case UP:
					g2.fillPolygon(new int[]{x, x + SIZE / 2, x + SIZE}, new int[]{y + SIZE - 1, y + 1, y + SIZE - 1}, 3);
					break;
				case DOWN:
					g2.fillPolygon(new int[]{x, x + SIZE / 2, x + SIZE}, new int[]{y + 1, y + SIZE - 1, y + 1}, 3);
					break;
				default:
					g2.setStroke(new BasicStroke(1.5f));
					g2.drawLine(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1);
					g2.drawLine(x + 1, y + SIZE - 1, x + SIZE - 1, y + 1);
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

	private static JPanel iconButtons(JButton... buttons)
	{
		JPanel panel = new JPanel(new GridLayout(1, buttons.length, 2, 0));
		panel.setOpaque(false);
		for (JButton button : buttons)
		{
			panel.add(button);
		}
		return panel;
	}

	private static JTextField field(int columns)
	{
		JTextField field = new JTextField(columns);
		field.setFont(FontManager.getRunescapeSmallFont());
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setBorder(FIELD_BORDER);
		return field;
	}

	/** A box with a small grey caption above it. */
	private static JPanel labelled(String caption, JComponent box)
	{
		JPanel panel = new JPanel(new BorderLayout(0, 1));
		panel.setOpaque(false);
		JLabel label = new JLabel(caption);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(label, BorderLayout.NORTH);
		panel.add(box, BorderLayout.CENTER);
		// Narrow sidebar: let boxes shrink rather than push the card wider than the panel
		box.setMinimumSize(new Dimension(10, box.getPreferredSize().height));
		box.setPreferredSize(new Dimension(10, box.getPreferredSize().height));
		return panel;
	}

	/** A box layout shifts every row if any one is aligned differently, so force them all left. */
	private static void leftAlign(Container container)
	{
		for (Component child : container.getComponents())
		{
			if (child instanceof JComponent)
			{
				((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
			}
			if (child instanceof Container)
			{
				leftAlign((Container) child);
			}
		}
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
