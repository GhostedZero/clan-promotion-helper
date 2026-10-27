package com.clanpromotionhelper;

import java.awt.Image;
import lombok.Value;

/**
 * One clan member as shown in the sidebar.
 */
@Value
class PromotionRow
{
	String name;
	String currentRank;
	/** The rank they'd move up to; null when there isn't one */
	String nextRank;
	int months;
	/** What the next rank needs, for the progress bars; 0 when there is no next rank */
	int requiredMonths;
	double requiredXpMillions;
	/** XP gained since the window start (join date or last rank change); null until a lookup succeeds */
	Long xpGained;
	/** Boss kills the next rank needs; 0 when bosses aren't part of its rule */
	int requiredBosses;
	/** Boss kills gained since the window start; null when unknown */
	Long bossKills;
	PromotionStatus status;
	/** Why a lookup failed, for the tooltip */
	String detail;
	/** The game's icon for the member's clan rank; null until RuneLite has loaded it */
	Image rankIcon;
	/** When this member's XP was last looked up (milliseconds since the epoch); 0 if never */
	long checkedAt;
}
