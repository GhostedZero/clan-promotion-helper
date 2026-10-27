package com.clanpromotionhelper;

import static com.clanpromotionhelper.PromotionPanel.CARD_BACKGROUND;
import static com.clanpromotionhelper.PromotionPanel.CARD_BORDER;
import static com.clanpromotionhelper.PromotionPanel.PANEL_BACKGROUND;
import static com.clanpromotionhelper.PromotionPanel.gap;
import static com.clanpromotionhelper.PromotionPanel.styleButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.Border;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Sidebar editor for the rank ladder and vanity ranks. It reads and writes the same two text settings as the
 * plugin's configuration panel, so either can be used, and checks every line before saving anything.
 */
@Singleton
class RulesEditorPanel extends JPanel
{
	private static final String RANK_OR_TIME = "Rank or time";
	private static final String LEVEL = "Level";
	private static final String KILLS = "Kill count";
	private static final String ALL_SKILLS = "All skills";
	private static final Border FIELD_BORDER = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(1, 1, 1, 1, CARD_BORDER), BorderFactory.createEmptyBorder(2, 4, 2, 4));
	private static final Border CARD = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(2, 2, 2, 2, CARD_BORDER), BorderFactory.createEmptyBorder(6, 8, 6, 8));
	private static final Border CARD_ERROR = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(2, 2, 2, 2, ColorScheme.PROGRESS_ERROR_COLOR), BorderFactory.createEmptyBorder(6, 8, 6, 8));

	private final ConfigManager configManager;
	private final ClanPromotionHelperConfig config;
	private final JPanel ladderList = column();
	private final JPanel vanityList = column();
	private final JLabel status = new JLabel(" ");
	private final List<LadderCard> ladder = new ArrayList<>();
	private final List<VanityCard> vanity = new ArrayList<>();
	private Supplier<List<String>> rankTitles = Collections::emptyList;
	private Supplier<List<String>> ladderTitles = Collections::emptyList;
	private Supplier<Set<String>> knownBosses = Collections::emptySet;
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
		ladderButtons.add(addButton("Add rank", () -> addLadder(new RankRules.Rank("", 0, 0, 0, false))));
		JButton fill = addButton("Fill from clan", this::fillFromClan);
		fill.setToolTipText("List your clan's ranks in their in-game order, keeping the requirements already set for them");
		ladderButtons.add(fill);
		content.add(ladderButtons);
		content.add(gap(12));
		content.add(heading("Vanity ranks", "Can be reached from any rank. Every condition is needed, "
			+ "and levels and kill counts are lifetime totals."));
		content.add(vanityList);
		content.add(addButton("Add vanity rank", () -> addVanity(new VanityRules.Rule("", Collections.singletonList(
			new VanityRules.Condition(VanityRules.Kind.RANK_OR_TIME, null, 6, Collections.emptyList(), Collections.emptyList(), 0))))));
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

	/** Where the rank-title choices and known boss names come from; both are read whenever the editor needs them. */
	void setSources(Supplier<List<String>> rankTitles, Supplier<List<String>> ladderTitles, Supplier<Set<String>> knownBosses)
	{
		this.rankTitles = rankTitles;
		this.ladderTitles = ladderTitles;
		this.knownBosses = knownBosses;
	}

	void setOnClose(Runnable onClose)
	{
		this.onClose = onClose;
	}

	/** Reloads both rule sets from the settings, dropping any unsaved edits. Swing thread only. */
	void open()
	{
		RankRules ranks = RankRules.parse(config.rankRequirements());
		VanityRules vanities = VanityRules.parse(config.vanityRanks());
		ladder.clear();
		ladderList.removeAll();
		vanity.clear();
		vanityList.removeAll();
		for (RankRules.Rank rank : ranks.getRanks())
		{
			addLadder(rank);
		}
		for (VanityRules.Rule rule : vanities.getRules())
		{
			addVanity(rule);
		}

		// Saving rewrites both settings from the cards, so lines the editor couldn't load would be lost
		int unreadable = ranks.getErrors().size() + vanities.getErrors().size();
		setStatus(unreadable == 0 ? " " : unreadable + " unreadable line" + (unreadable == 1 ? "" : "s") + " will be removed on save",
			unreadable == 0 ? null : String.join("\n", ranks.getErrors()) + "\n" + String.join("\n", vanities.getErrors()));
		refresh();
	}

	private void save()
	{
		List<String> problems = new ArrayList<>();
		List<String> ladderLines = new ArrayList<>();
		for (int i = 0; i < ladder.size(); i++)
		{
			LadderCard card = ladder.get(i);
			String line = card.toLine();
			List<String> errors = RankRules.parse(line).getErrors();
			String problem = card.name.getText().trim().isEmpty() ? "needs a name" : errors.isEmpty() ? null : "check the numbers";
			card.mark(problem);
			if (problem != null)
			{
				problems.add("Rank " + (i + 1) + ": " + problem);
			}
			ladderLines.add(line);
		}

		Set<String> bosses = knownBosses.get();
		Set<String> titles = new HashSet<>();
		for (String title : rankTitles.get())
		{
			titles.add(title.toLowerCase(Locale.ROOT));
		}
		List<String> vanityLines = new ArrayList<>();
		for (VanityCard card : vanity)
		{
			String name = card.name.getText().trim();
			String problem = name.isEmpty() ? "needs a name" : name.contains(":") ? "name can't contain \":\"" : null;
			List<VanityRules.Condition> conditions = new ArrayList<>();
			for (ConditionRow row : card.conditions)
			{
				String text = row.toText();
				VanityRules.Condition condition = text == null ? null : VanityRules.parseCondition(text);
				String rowProblem = condition == null ? "fill in every box with a number where one is needed"
					: unknownBosses(condition, bosses);
				// Titles are only known once the clan has loaded; before that any title is allowed
				if (rowProblem == null && !titles.isEmpty())
				{
					rowProblem = VanityRules.unknownRank(condition, titles);
				}
				row.mark(rowProblem);
				problem = problem == null ? rowProblem : problem;
				if (condition != null)
				{
					conditions.add(condition);
				}
			}
			if (problem == null && conditions.isEmpty())
			{
				problem = "needs a condition";
			}
			card.mark(problem);
			if (problem != null)
			{
				problems.add((name.isEmpty() ? "Vanity rank" : name) + ": " + problem);
			}
			vanityLines.add(new VanityRules.Rule(name, conditions).toText());
		}

		if (!problems.isEmpty())
		{
			setStatus(problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + " (hover)", String.join("\n", problems));
			refresh();
			return;
		}

		configManager.setConfiguration(ClanPromotionHelperConfig.GROUP, "rankRequirements", String.join("\n", ladderLines));
		configManager.setConfiguration(ClanPromotionHelperConfig.GROUP, "vanityRanks", String.join("\n", vanityLines));
		setStatus(" ", null);
		onClose.run();
	}

	/** Only checked once Wise Old Man has returned its boss list this session; before that any name is allowed. */
	private static String unknownBosses(VanityRules.Condition condition, Set<String> known)
	{
		if (condition.getKind() != VanityRules.Kind.KILLS || known.isEmpty())
		{
			return null;
		}
		List<String> unknown = new ArrayList<>();
		for (String name : condition.getNames())
		{
			if (!name.equals("all") && !name.equals("raids") && !known.contains(name))
			{
				unknown.add(name);
			}
		}
		return unknown.isEmpty() ? null : "unknown boss " + String.join(", ", unknown);
	}

	/**
	 * Replaces the ladder with the clan's in-game ranks, lowest first, keeping each existing card's requirements.
	 * Vanity ranks are left out. Nothing is saved until Save, so Cancel undoes it.
	 */
	void fillFromClan()
	{
		List<String> titles = ladderTitles.get();
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
		Set<String> vanityNames = new HashSet<>();
		for (VanityCard card : vanity)
		{
			vanityNames.add(card.name.getText().trim().toLowerCase(Locale.ROOT));
		}

		List<LadderCard> filled = new ArrayList<>();
		for (String title : clanLadder(titles, vanityNames))
		{
			LadderCard card = existing.remove(title.toLowerCase(Locale.ROOT));
			if (card == null)
			{
				card = new LadderCard(new RankRules.Rank(title, 0, 0, 0, false));
			}
			card.name.setText(title);
			filled.add(card);
		}
		ladder.clear();
		ladder.addAll(filled);
		relist(ladderList, ladder);

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

	/** The clan's titles in order, minus the ones used as vanity ranks (given in lower case). */
	static List<String> clanLadder(List<String> titles, Set<String> vanityNames)
	{
		List<String> ladder = new ArrayList<>();
		for (String title : titles)
		{
			if (!vanityNames.contains(title.toLowerCase(Locale.ROOT)))
			{
				ladder.add(title);
			}
		}
		return ladder;
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
		relist(ladderList, ladder);
	}

	private void addVanity(VanityRules.Rule rule)
	{
		VanityCard card = new VanityCard(rule);
		vanity.add(card);
		vanityList.add(card);
		vanityList.add(gap(6));
		refresh();
	}

	private void relist(JPanel list, List<? extends JComponent> cards)
	{
		list.removeAll();
		for (JComponent card : cards)
		{
			list.add(card);
			list.add(gap(6));
		}
		refresh();
	}

	private void refresh()
	{
		leftAlign(this);
		revalidate();
		repaint();
	}

	/** One rung of the ladder: name and order on top, then months, XP, kills and how XP and kills combine. */
	private final class LadderCard extends JPanel
	{
		private final JTextField name = field(20);
		private final JTextField months = field(3);
		private final JTextField xp = field(4);
		private final JTextField kills = field(4);
		// A toggle rather than a drop-down: a drop-down's arrow leaves no room for the text in a quarter of the sidebar
		private final JButton logic = new JButton();

		LadderCard(RankRules.Rank rank)
		{
			super(new BorderLayout(0, 4));
			card(this);
			name.setText(rank.getName());
			months.setText(String.valueOf(rank.getMonths()));
			xp.setText(BigDecimal.valueOf(rank.getXpMillions()).stripTrailingZeros().toPlainString());
			kills.setText(String.valueOf(rank.getBosses()));
			logic.setText(rank.isEither() ? "OR" : "AND");
			logic.setToolTipText("AND needs both the XP and the kills, OR needs either. Click to switch.");
			logic.setFont(FontManager.getRunescapeSmallFont());
			logic.setFocusPainted(false);
			logic.setForeground(Color.WHITE);
			logic.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			logic.setBorder(FIELD_BORDER);
			logic.addActionListener(e -> logic.setText("OR".equals(logic.getText()) ? "AND" : "OR"));

			JPanel top = new JPanel(new BorderLayout(4, 0));
			top.setOpaque(false);
			top.add(name, BorderLayout.CENTER);
			top.add(iconButtons(
				iconButton("▲", "Move up", () -> moveLadder(this, -1)),
				iconButton("▼", "Move down", () -> moveLadder(this, 1)),
				iconButton("✕", "Remove", () ->
				{
					ladder.remove(this);
					relist(ladderList, ladder);
				})), BorderLayout.EAST);

			JPanel numbers = new JPanel(new GridLayout(1, 4, 4, 0));
			numbers.setOpaque(false);
			numbers.add(labelled("Months", months));
			numbers.add(labelled("XP (M)", xp));
			numbers.add(labelled("Kills", kills));
			numbers.add(labelled("Logic", logic));

			add(top, BorderLayout.NORTH);
			add(numbers, BorderLayout.CENTER);
		}

		String toLine()
		{
			String line = name.getText().trim() + ":" + months.getText().trim() + ":" + xp.getText().trim();
			String kc = kills.getText().trim();
			boolean either = "OR".equals(logic.getText());
			// The short form keeps lines written in the old format looking the same
			return kc.equals("0") && !either ? line : line + ":" + kc + ":" + logic.getText();
		}

		void mark(String problem)
		{
			setBorder(problem == null ? CARD : CARD_ERROR);
			setToolTipText(problem);
		}
	}

	/** A vanity rank: its name, then one row per condition. */
	private final class VanityCard extends JPanel
	{
		private final JTextField name = field(20);
		private final JPanel rows = column();
		private final List<ConditionRow> conditions = new ArrayList<>();

		VanityCard(VanityRules.Rule rule)
		{
			super(new BorderLayout(0, 4));
			card(this);
			name.setText(rule.getName());

			JPanel top = new JPanel(new BorderLayout(4, 0));
			top.setOpaque(false);
			top.add(name, BorderLayout.CENTER);
			top.add(iconButtons(iconButton("✕", "Remove", () ->
			{
				vanity.remove(this);
				relist(vanityList, vanity);
			})), BorderLayout.EAST);

			for (VanityRules.Condition condition : rule.getConditions())
			{
				addCondition(condition);
			}

			JPanel south = column();
			south.add(rows);
			south.add(addButton("Add condition", () -> addCondition(
				new VanityRules.Condition(VanityRules.Kind.LEVEL, null, 0, Collections.emptyList(), Collections.emptyList(), 99))));
			add(top, BorderLayout.NORTH);
			add(south, BorderLayout.CENTER);
		}

		private void addCondition(VanityRules.Condition condition)
		{
			ConditionRow row = new ConditionRow(condition, this);
			conditions.add(row);
			rows.add(row);
			refresh();
		}

		void removeCondition(ConditionRow row)
		{
			conditions.remove(row);
			rows.remove(row);
			refresh();
		}

		void mark(String problem)
		{
			setBorder(problem == null ? CARD : CARD_ERROR);
			setToolTipText(problem);
		}
	}

	/** One condition: its kind, then the boxes that kind needs. */
	private final class ConditionRow extends JPanel
	{
		private final JComboBox<String> kind = combo(new String[]{RANK_OR_TIME, LEVEL, KILLS});
		private final JPanel boxes = new JPanel(new BorderLayout(0, 3));
		private final JComboBox<String> rank = combo(new String[0]);
		private final JTextField months = field(3);
		private final JComboBox<String> skill = combo(skillChoices());
		private final JTextField level = field(3);
		private final JTextField except = field(10);
		private final JTextField bosses = field(10);
		private final JTextField count = field(5);

		ConditionRow(VanityRules.Condition condition, VanityCard owner)
		{
			super(new BorderLayout(0, 3));
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));

			rank.setEditable(true);
			// Typed titles go in the editor box, which otherwise stays the look and feel's light colour
			Component editorBox = rank.getEditor().getEditorComponent();
			editorBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			editorBox.setForeground(Color.WHITE);
			rank.addItem("");
			for (String title : rankTitles.get())
			{
				rank.addItem(title);
			}
			rank.setToolTipText("This rank or any higher one (by in-game rank order) counts. Leave blank for time only.");
			months.setToolTipText("Months in the clan since joining. 0 for rank only.");
			except.setToolTipText("Skills that don't count, separated by commas, e.g. sailing");
			bosses.setToolTipText("all, raids, or boss names separated by commas, e.g. TzKal-Zuk, Sol Heredit");
			count.setToolTipText("Total kill count over those bosses");
			boxes.setOpaque(false);

			switch (condition.getKind())
			{
				case RANK_OR_TIME:
					kind.setSelectedItem(RANK_OR_TIME);
					rank.setSelectedItem(condition.getRank() == null ? "" : condition.getRank());
					months.setText(String.valueOf(condition.getMonths()));
					break;
				case LEVEL:
					kind.setSelectedItem(LEVEL);
					skill.setSelectedItem(condition.getNames().isEmpty() ? ALL_SKILLS : condition.getNames().get(0));
					level.setText(String.valueOf(condition.getValue()));
					except.setText(String.join(", ", condition.getExcept()));
					break;
				default:
					kind.setSelectedItem(KILLS);
					bosses.setText(String.join(", ", condition.getNames()));
					count.setText(String.valueOf(condition.getValue()));
					break;
			}
			kind.addActionListener(e -> layoutBoxes());
			skill.addActionListener(e -> layoutBoxes());

			JPanel top = new JPanel(new BorderLayout(4, 0));
			top.setOpaque(false);
			top.add(kind, BorderLayout.CENTER);
			top.add(iconButtons(iconButton("✕", "Remove condition", () -> owner.removeCondition(this))), BorderLayout.EAST);
			add(top, BorderLayout.NORTH);
			add(boxes, BorderLayout.CENTER);
			layoutBoxes();
		}

		private void layoutBoxes()
		{
			boxes.removeAll();
			JPanel line = new JPanel(new GridLayout(1, 2, 4, 0));
			line.setOpaque(false);
			Object selected = kind.getSelectedItem();
			if (RANK_OR_TIME.equals(selected))
			{
				line.add(labelled("Rank or higher", rank));
				line.add(labelled("or months", months));
				boxes.add(line, BorderLayout.NORTH);
			}
			else if (LEVEL.equals(selected))
			{
				line.add(labelled("Skill", skill));
				line.add(labelled("Level", level));
				boxes.add(line, BorderLayout.NORTH);
				if (ALL_SKILLS.equals(skill.getSelectedItem()))
				{
					boxes.add(labelled("Except", except), BorderLayout.CENTER);
				}
			}
			else
			{
				boxes.add(labelled("Bosses", bosses), BorderLayout.NORTH);
				boxes.add(labelled("Total kills", count), BorderLayout.CENTER);
			}
			refresh();
		}

		/** The condition in the vanity text format, or null when a box it needs is empty. */
		String toText()
		{
			Object selected = kind.getSelectedItem();
			if (RANK_OR_TIME.equals(selected))
			{
				String title = rank.getSelectedItem() == null ? "" : rank.getSelectedItem().toString().trim();
				String mo = months.getText().trim();
				boolean noMonths = mo.isEmpty() || mo.equals("0");
				// Otherwise "6m" would be read as part of the rank title
				if (!noMonths && !mo.matches("\\d+"))
				{
					return null;
				}
				if (title.isEmpty())
				{
					return noMonths ? null : mo + " months";
				}
				return noMonths ? "rank " + title : "rank " + title + " or " + mo + " months";
			}
			if (LEVEL.equals(selected))
			{
				String skills = ALL_SKILLS.equals(skill.getSelectedItem())
					? "all" + (except.getText().trim().isEmpty() ? "" : " except " + except.getText().trim())
					: String.valueOf(skill.getSelectedItem());
				return level.getText().trim().isEmpty() ? null : "level " + skills + " " + level.getText().trim();
			}
			return bosses.getText().trim().isEmpty() || count.getText().trim().isEmpty() ? null
				: "kills " + bosses.getText().trim() + " " + count.getText().trim();
		}

		void mark(String problem)
		{
			setToolTipText(problem);
			kind.setForeground(problem == null ? Color.WHITE : ColorScheme.PROGRESS_ERROR_COLOR);
		}
	}

	private static String[] skillChoices()
	{
		List<String> choices = new ArrayList<>();
		choices.add(ALL_SKILLS);
		choices.addAll(VanityRules.SKILLS);
		return choices.toArray(new String[0]);
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		return panel;
	}

	private static void card(JPanel panel)
	{
		panel.setBackground(CARD_BACKGROUND);
		panel.setBorder(CARD);
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

	private static JButton iconButton(String glyph, String tooltip, Runnable action)
	{
		JButton button = new JButton(glyph);
		// The RuneScape fonts have no arrow or cross glyphs, so these use the default font
		button.setFont(button.getFont().deriveFont(Font.PLAIN, 10f));
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, CARD_BORDER));
		button.setPreferredSize(new Dimension(20, 20));
		button.addActionListener(e -> action.run());
		return button;
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

	private static JComboBox<String> combo(String[] items)
	{
		JComboBox<String> combo = new JComboBox<>(items);
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setForeground(Color.WHITE);
		combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		combo.setFocusable(false);
		return combo;
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
