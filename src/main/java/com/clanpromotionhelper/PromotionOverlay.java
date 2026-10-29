package com.clanpromotionhelper;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class PromotionOverlay extends OverlayPanel
{
	private static final int PANEL_WIDTH = 190;

	private final ClanPromotionHelperPlugin plugin;
	private final ClanPromotionHelperConfig config;

	@Inject
	PromotionOverlay(ClanPromotionHelperPlugin plugin, ClanPromotionHelperConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		int memberCount = plugin.getMembers().size();
		List<ClanPromotionHelperPlugin.Candidate> candidates = plugin.getCandidates();
		// Nothing to show when nobody is due a promotion; the sidebar has the full picture
		if (!config.showOverlay() || memberCount == 0 || candidates.isEmpty())
		{
			return null;
		}

		panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Promotions due: " + candidates.size())
			.build());

		// Out of the members who can be promoted, since staff and top ranks aren't looked up
		int checkable = plugin.getCheckableCount();
		if (plugin.isFetching() || plugin.getCheckedCount() < checkable)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("XP checked")
				.right(plugin.getCheckedCount() + "/" + checkable)
				.build());
		}

		int max = config.maxDisplayed();
		int shown = max == 0 ? candidates.size() : Math.min(max, candidates.size());
		for (int i = 0; i < shown; i++)
		{
			ClanPromotionHelperPlugin.Candidate candidate = candidates.get(i);
			panelComponent.getChildren().add(LineComponent.builder()
				.left(candidate.getName())
				.right(candidate.getNextRank())
				.rightColor(config.highlightColor())
				.build());
		}

		if (shown < candidates.size())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("+" + (candidates.size() - shown) + " more")
				.build());
		}

		return super.render(graphics);
	}
}
