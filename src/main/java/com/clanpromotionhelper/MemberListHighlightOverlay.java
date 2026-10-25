package com.clanpromotionhelper;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

/**
 * Highlights members who are due a promotion in the Clan Settings member list.
 * The approach of reading the member-name column follows the Clan Rank Helper plugin (BSD-2).
 */
class MemberListHighlightOverlay extends Overlay
{
	private static final int FILL_ALPHA = 60;

	private final Client client;
	private final ClanPromotionHelperPlugin plugin;
	private final ClanPromotionHelperConfig config;

	@Inject
	MemberListHighlightOverlay(Client client, ClanPromotionHelperPlugin plugin, ClanPromotionHelperConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Map<String, String> targets = plugin.getCandidateTargets();
		if (!config.highlightMemberList() || targets.isEmpty())
		{
			return null;
		}

		Widget scrollContents = client.getWidget(InterfaceID.ClansMembers.SCROLLCONTENTS);
		Widget nameColumn = client.getWidget(InterfaceID.ClansMembers.NAME);
		if (scrollContents == null || scrollContents.isHidden() || nameColumn == null || nameColumn.isHidden())
		{
			return null;
		}

		Rectangle viewport = scrollContents.getBounds();
		if (viewport == null || viewport.isEmpty())
		{
			return null;
		}

		Color outline = config.highlightColor();
		Color fill = new Color(outline.getRed(), outline.getGreen(), outline.getBlue(), FILL_ALPHA);
		Shape previousClip = graphics.getClip();
		graphics.setClip(viewport);

		for (Widget nameWidget : textWidgets(nameColumn))
		{
			String name = Text.removeTags(nameWidget.getText());
			if (!targets.containsKey(XpCache.key(name)))
			{
				continue;
			}

			Rectangle bounds = nameWidget.getBounds();
			if (bounds == null || !viewport.intersects(bounds))
			{
				continue;
			}

			graphics.setColor(fill);
			graphics.fill(bounds);
			graphics.setColor(outline);
			graphics.draw(bounds);
		}

		graphics.setClip(previousClip);
		return null;
	}

	private static List<Widget> textWidgets(Widget parent)
	{
		List<Widget> found = new ArrayList<>();
		collect(parent.getDynamicChildren(), found);
		collect(parent.getStaticChildren(), found);
		collect(parent.getNestedChildren(), found);
		return found;
	}

	private static void collect(Widget[] children, List<Widget> found)
	{
		if (children == null)
		{
			return;
		}

		for (Widget child : children)
		{
			if (child != null && !child.isHidden() && child.getText() != null && !child.getText().isEmpty())
			{
				found.add(child);
			}
		}
	}
}
