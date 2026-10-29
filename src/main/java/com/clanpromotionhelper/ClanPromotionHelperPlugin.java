package com.clanpromotionhelper;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Clan Promotion Helper",
	description = "Works out which clan members are due a promotion from their time in the clan and XP gained",
	tags = {"clan", "rank", "promotion", "wise old man", "xp"}
)
public class ClanPromotionHelperPlugin extends Plugin
{
	private static final String CACHE_KEY = "xpCache";
	/** How often the roster is re-read from clan settings (about 1.8 seconds) */
	private static final int ROSTER_CHECK_TICKS = 3;
	/** How often to look for members with no result yet, such as new members, unless the roster just changed (about 30 seconds) */
	private static final int STALE_CHECK_TICKS = 50;
	/**
	 * Spacing between lookups: about 17 a minute without a key and 80 with one. Wise Old Man's limits are 20 and
	 * 100 a minute per internet address, so this leaves room for other plugins that use it from the same address.
	 */
	private static final long DELAY_WITHOUT_KEY_MS = 3_500;
	private static final long DELAY_WITH_KEY_MS = 750;
	private static final int MAX_RETRIES = 2;
	/** Refresh now can be used once an hour; saved, so restarting RuneLite doesn't reset it */
	private static final long MANUAL_REFRESH_COOLDOWN_MS = TimeUnit.HOURS.toMillis(1);
	private static final String LAST_MANUAL_REFRESH_KEY = "lastManualRefresh";
	/** Save the cache after this many new results, not after every one */
	private static final int SAVE_EVERY_RESULTS = 25;

	@Value
	static class Member
	{
		String name;
		String rank;
		LocalDate joinDate;
		/** The game's icon for the member's clan rank; null if RuneLite hasn't loaded it */
		BufferedImage rankIcon;
	}

	@Value
	static class Candidate
	{
		String name;
		String currentRank;
		String nextRank;
	}

	@Inject
	private Client client;

	@Inject
	private ClanPromotionHelperConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ChatIconManager chatIconManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PromotionPanel promotionPanel;

	@Inject
	private PromotionOverlay promotionOverlay;

	@Inject
	private MemberListHighlightOverlay memberListHighlightOverlay;

	@Inject
	private WiseOldManClient wiseOldManClient;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Getter
	private volatile List<Member> members = Collections.emptyList();

	@Getter
	private volatile List<Candidate> candidates = Collections.emptyList();

	/** Next rank keyed by {@link XpCache#key}, for the member list highlight */
	@Getter
	private volatile Map<String, String> candidateTargets = Collections.emptyMap();

	/** Current rank title of each member in {@link #candidateTargets}, keyed the same way */
	@Getter
	private volatile Map<String, String> candidateCurrentRanks = Collections.emptyMap();

	@Getter
	private volatile int checkedCount;

	@Getter
	private volatile int failedCount;
	/** Members who can be promoted, and so are looked up */
	@Getter
	private volatile int checkableCount;

	@Getter
	private volatile boolean fetching;

	/** Every member as shown in the sidebar */
	private volatile List<PromotionRow> rows = Collections.emptyList();

	/** One-line note about the last Wise Old Man group refresh; null when no group is set */
	@Getter
	private volatile String groupStatus;

	private volatile RankRules rankRules;
	private volatile XpCache xpCache = new XpCache();
	private String loadedProfileKey;
	private int rosterHash;
	private int ticksUntilRosterCheck;
	private int ticksUntilStaleCheck;
	/**
	 * Results fetched before this are due their daily refresh. It's the start of the day the player logged in and
	 * stays fixed for the session, so members are refreshed once a day, at the first login that day.
	 */
	private volatile long refreshBefore;
	/** Results fetched before this time count as stale for a Refresh now */
	private volatile long forceRefreshBefore;
	private volatile long lastManualRefresh;

	private final Deque<Member> fetchQueue = new ArrayDeque<>();
	private NavigationButton navigationButton;
	private ScheduledFuture<?> scheduledFetch;
	/** Bumped whenever fetching stops, so late responses from an old run are ignored */
	private int fetchGeneration;
	private int unsavedResults;
	/** Members the group request updated in the current run; null when this run didn't use the group */
	private Integer groupUpdatedThisRun;
	/** Size of the current run's one-by-one lookups, and how many of them failed */
	private int individualTotal;
	private int individualFailed;

	@Provides
	ClanPromotionHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanPromotionHelperConfig.class);
	}

	@Override
	protected void startUp()
	{
		rankRules = RankRules.parse(config.rankRequirements());
		Long savedRefresh = configManager.getConfiguration(ClanPromotionHelperConfig.GROUP, LAST_MANUAL_REFRESH_KEY, Long.class);
		lastManualRefresh = savedRefresh == null ? 0 : savedRefresh;
		ticksUntilRosterCheck = 1;
		overlayManager.add(promotionOverlay);
		overlayManager.add(memberListHighlightOverlay);

		navigationButton = NavigationButton.builder()
			.tooltip("Clan Promotion Helper")
			.icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
			.priority(7)
			.panel(promotionPanel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		promotionPanel.start();
		promotionPanel.setRefreshAction(() -> executor.submit(this::requestRefresh));
		recompute();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(promotionOverlay);
		overlayManager.remove(memberListHighlightOverlay);
		clientToolbar.removeNavigation(navigationButton);
		stopFetching();
		saveCache();
		clearState();
		// Last, so the update clearState sends can't restart the panel's refresh timer
		promotionPanel.stop();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			stopFetching();
			saveCache();
			clearState();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (--ticksUntilRosterCheck > 0)
		{
			return;
		}
		ticksUntilRosterCheck = ROSTER_CHECK_TICKS;

		if (!loadCacheForProfile())
		{
			return;
		}

		ClanSettings settings = client.getClanSettings();
		if (settings == null)
		{
			return;
		}

		List<Member> roster = readRoster(settings);
		boolean rosterChanged = roster.hashCode() != rosterHash;
		if (rosterChanged)
		{
			rosterHash = roster.hashCode();
			members = Collections.unmodifiableList(roster);

			List<String> names = new ArrayList<>();
			for (Member member : roster)
			{
				names.add(member.getName());
			}
			xpCache.retainOnly(names);
			recompute();
		}

		// Refreshes happen once a day, so there's no need to scan every member on every roster check
		ticksUntilStaleCheck -= ROSTER_CHECK_TICKS;
		if (rosterChanged || ticksUntilStaleCheck <= 0)
		{
			ticksUntilStaleCheck = STALE_CHECK_TICKS;
			queueStaleMembers();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ClanPromotionHelperConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		switch (event.getKey())
		{
			case "rankRequirements":
				rankRules = RankRules.parse(config.rankRequirements());
				recompute();
				// Members whose rank the new table can promote need looking up
				ticksUntilStaleCheck = 0;
				break;
			case "enableWiseOldMan":
				if (!config.enableWiseOldMan())
				{
					stopFetching();
				}
				ticksUntilStaleCheck = 0;
				break;
			case "ignoredPlayers":
			case "ignoredTargetRanks":
				recompute();
				break;
			default:
				break;
		}
	}

	private List<Member> readRoster(ClanSettings settings)
	{
		List<Member> roster = new ArrayList<>();
		for (ClanMember clanMember : settings.getMembers())
		{
			if (clanMember == null || clanMember.getName() == null || clanMember.getJoinDate() == null)
			{
				continue;
			}

			ClanTitle title = settings.titleForRank(clanMember.getRank());
			String name = clanMember.getName().replace('\u00A0', ' ').trim();
			BufferedImage icon = title == null ? null : chatIconManager.getRankImage(title);
			roster.add(new Member(name, title == null ? null : title.getName(), clanMember.getJoinDate(), icon));
		}
		return roster;
	}

	/**
	 * Loads the saved XP for the logged-in account, switching caches if the account changed.
	 * Returns false until RuneLite knows which account is logged in.
	 */
	private boolean loadCacheForProfile()
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null)
		{
			return false;
		}

		if (!profileKey.equals(loadedProfileKey))
		{
			stopFetching();
			saveCache();
			XpCache loaded = XpCache.fromJson(configManager.getConfiguration(ClanPromotionHelperConfig.GROUP, profileKey, CACHE_KEY), gson);
			// Under the lock, so a lookup that finishes during the switch can't land in the new account's cache
			synchronized (this)
			{
				xpCache = loaded;
				loadedProfileKey = profileKey;
			}
			refreshBefore = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			rosterHash = 0;
		}
		return true;
	}

	/** Saves to the account the cache was loaded for, even if RuneLite has already moved on from it */
	private void saveCache()
	{
		String profileKey = loadedProfileKey;
		if (profileKey == null)
		{
			return;
		}
		configManager.setConfiguration(ClanPromotionHelperConfig.GROUP, profileKey, CACHE_KEY, xpCache.toJson(gson));
		synchronized (this)
		{
			unsavedResults = 0;
		}
	}

	private void clearState()
	{
		members = Collections.emptyList();
		candidates = Collections.emptyList();
		candidateTargets = Collections.emptyMap();
		candidateCurrentRanks = Collections.emptyMap();
		rows = Collections.emptyList();
		groupStatus = null;
		checkedCount = 0;
		failedCount = 0;
		checkableCount = 0;
		xpCache = new XpCache();
		loadedProfileKey = null;
		rosterHash = 0;
		promotionPanel.update(rows, summary());
	}

	private List<Member> staleMembers()
	{
		RankRules rules = rankRules;
		// With a group, a forced refresh can cover everyone cheaply. Without one it would mean one request
		// per member, so it only retries members who have no result yet or whose lookup failed.
		boolean everyone = config.womGroupId() > 0;
		List<Member> stale = new ArrayList<>();
		for (Member member : members)
		{
			// Staff and top ranks can't be promoted, so their XP would never be used
			if (!rules.canBePromoted(member.getRank()))
			{
				continue;
			}

			XpCache.Entry entry = xpCache.get(member.getName());
			boolean forced = entry != null && entry.getFetchedAt() < forceRefreshBefore
				&& (everyone || entry.getXpGained() == null);
			if (forced || xpCache.needsFetch(member.getName(), member.getJoinDate(), refreshBefore))
			{
				stale.add(member);
			}
		}
		return stale;
	}

	/** The sidebar's Refresh button: re-checks XP now. Ignored while a refresh is running or within an hour of the last one. */
	private void requestRefresh()
	{
		synchronized (this)
		{
			long now = System.currentTimeMillis();
			if (fetching || !config.enableWiseOldMan())
			{
				return;
			}

			if (now < refreshAvailableAt())
			{
				// The button is disabled until then, so this only catches a click that raced the update
				return;
			}

			lastManualRefresh = now;
			configManager.setConfiguration(ClanPromotionHelperConfig.GROUP, LAST_MANUAL_REFRESH_KEY, now);
			forceRefreshBefore = now;
			groupStatus = config.womGroupId() > 0 ? null : "Retrying members with no result";
		}
		recompute();
		queueStaleMembers();
		recompute();
	}

	/**
	 * Starts a refresh when someone is due one. With a Wise Old Man group set, a single group request first
	 * brings everyone who has a saved baseline up to date; only the members it can't cover (no baseline yet,
	 * not in the group, unusable total) are then looked up one by one.
	 */
	private synchronized void queueStaleMembers()
	{
		if (fetching || !config.enableWiseOldMan())
		{
			return;
		}

		List<Member> stale = staleMembers();
		if (stale.isEmpty())
		{
			return;
		}

		fetching = true;
		int groupId = config.womGroupId();
		if (groupId > 0 && hasBaseline(stale))
		{
			groupStatus = "Refreshing from group " + groupId + "\u2026";
			int generation = fetchGeneration;
			wiseOldManClient.fetchGroup(groupId, config.apiKey(), config.contact(),
				result -> onGroupResult(generation, result));
			return;
		}

		groupUpdatedThisRun = null;
		groupStatus = groupId > 0 ? "Saving starting XP for group refreshes" : null;
		startIndividualLookups(stale);
	}

	private boolean hasBaseline(List<Member> stale)
	{
		for (Member member : stale)
		{
			if (xpCache.baselineFor(member.getName(), member.getJoinDate()) != null)
			{
				return true;
			}
		}
		return false;
	}

	private void onGroupResult(int generation, WiseOldManClient.GroupResult result)
	{
		synchronized (this)
		{
			if (generation != fetchGeneration)
			{
				return;
			}

			long now = System.currentTimeMillis();
			if (result.isOk())
			{
				int updated = 0;
				for (Member member : members)
				{
					if (xpCache.applyGroupXp(member.getName(), member.getJoinDate(), result.getExp(), now))
					{
						updated++;
					}
				}

				int remaining = staleMembers().size();
				groupUpdatedThisRun = updated;
				groupStatus = "Group: " + updated + " updated" + (remaining > 0 ? ", looking up " + remaining + "\u2026" : "");
			}
			else
			{
				log.debug("Wise Old Man group refresh failed: {}", result.getError());
				groupUpdatedThisRun = null;
				groupStatus = "Group refresh failed: " + result.getError();
			}

			startIndividualLookups(staleMembers());
		}
		recompute();
	}

	/** Queues the given members for one-by-one lookups, or finishes the refresh if there are none. */
	private synchronized void startIndividualLookups(List<Member> toLookUp)
	{
		fetchQueue.clear();
		fetchQueue.addAll(toLookUp);
		individualTotal = toLookUp.size();
		individualFailed = 0;

		if (fetchQueue.isEmpty())
		{
			// Nothing left after the group request, so its line ("Group: N updated") is already final
			groupUpdatedThisRun = null;
			fetching = false;
			saveCache();
			return;
		}

		log.debug("Looking up XP for {} clan members", fetchQueue.size());
		fetching = true;
		scheduleNext(0, fetchGeneration);
	}

	private synchronized void stopFetching()
	{
		fetchQueue.clear();
		if (scheduledFetch != null)
		{
			scheduledFetch.cancel(false);
			scheduledFetch = null;
		}
		fetching = false;
		fetchGeneration++;
	}

	/**
	 * Schedules the next lookup for the run that {@code generation} belongs to. A run that has since been
	 * stopped must not schedule anything, or it would keep going alongside the next run and double the
	 * request rate.
	 */
	private synchronized void scheduleNext(long delayMs, int generation)
	{
		if (generation != fetchGeneration)
		{
			return;
		}
		scheduledFetch = executor.schedule(() -> fetchNext(generation), delayMs, TimeUnit.MILLISECONDS);
	}

	private void fetchNext(int generation)
	{
		Member member;
		synchronized (this)
		{
			if (generation != fetchGeneration)
			{
				return;
			}

			member = fetchQueue.poll();
			if (member == null)
			{
				fetching = false;
				// The group line was written before these lookups ran; say how they went now they're done
				if (groupUpdatedThisRun != null)
				{
					groupStatus = "Group: " + groupUpdatedThisRun + " updated, " + individualTotal + " looked up"
						+ (individualFailed > 0 ? " (" + individualFailed + " failed)" : "");
					groupUpdatedThisRun = null;
				}
			}
		}

		if (member == null)
		{
			saveCache();
			recompute();
			return;
		}

		lookUp(member, 0, generation);
	}

	private void lookUp(Member member, int attempt, int generation)
	{
		// Wise Old Man rejects a range that starts and ends on the same day, which would fail everyone who
		// joined today; ending tomorrow also counts XP gained so far today
		wiseOldManClient.fetchXpGained(member.getName(), member.getJoinDate(), LocalDate.now().plusDays(1),
			config.apiKey(), config.contact(), result -> onResult(member, attempt, generation, result));
	}

	private void onResult(Member member, int attempt, int generation, WiseOldManClient.Result result)
	{
		synchronized (this)
		{
			if (generation != fetchGeneration)
			{
				return;
			}

			if (result.getStatus() == WiseOldManClient.Result.Status.RETRY && attempt < MAX_RETRIES)
			{
				log.debug("Retrying {} in {}s: {}", member.getName(), result.getRetryAfterSeconds(), result.getError());
				scheduledFetch = executor.schedule(() -> lookUp(member, attempt + 1, generation),
					result.getRetryAfterSeconds(), TimeUnit.SECONDS);
				return;
			}

			// Written while still holding the lock that checked the run, so stopping or switching accounts
			// can't happen between the check and the write
			long now = System.currentTimeMillis();
			String joinDate = member.getJoinDate().toString();
			// A baseline stays valid for this name and join date, so a failed lookup mustn't throw it away
			Long savedBaseline = xpCache.baselineFor(member.getName(), member.getJoinDate());
			if (result.getStatus() == WiseOldManClient.Result.Status.OK)
			{
				Long baseline = result.getStartXp() != null ? result.getStartXp() : savedBaseline;
				xpCache.put(member.getName(), new XpCache.Entry(result.getXpGained(), now, joinDate, null, baseline));
			}
			else
			{
				log.debug("XP lookup for {} failed: {}", member.getName(), result.getError());
				xpCache.put(member.getName(), new XpCache.Entry(null, now, joinDate, result.getError(), savedBaseline));
				individualFailed++;
			}
		}

		recompute();

		boolean save;
		synchronized (this)
		{
			save = ++unsavedResults >= SAVE_EVERY_RESULTS;
		}
		if (save)
		{
			saveCache();
		}

		String apiKey = config.apiKey();
		scheduleNext(apiKey == null || apiKey.trim().isEmpty() ? DELAY_WITHOUT_KEY_MS : DELAY_WITH_KEY_MS, generation);
	}

	private void recompute()
	{
		RankRules rules = rankRules;
		XpCache cache = xpCache;
		Set<String> ignoredPlayers = splitList(config.ignoredPlayers(), true);
		Set<String> ignoredTargets = splitList(config.ignoredTargetRanks(), false);
		LocalDate today = LocalDate.now();

		List<PromotionRow> allRows = new ArrayList<>();
		List<Candidate> found = new ArrayList<>();
		Map<String, String> targets = new HashMap<>();
		Map<String, String> currentRanks = new HashMap<>();
		int checked = 0;
		int failed = 0;
		int checkable = 0;

		for (Member member : members)
		{
			// Staff and top ranks aren't looked up, so a result saved before that (or under an older rank table) is ignored
			boolean promotable = rules.canBePromoted(member.getRank());
			if (promotable)
			{
				checkable++;
			}
			XpCache.Entry entry = promotable ? cache.get(member.getName()) : null;
			Long xp = entry == null ? null : entry.getXpGained();
			RankRules.Evaluation evaluation = rules.evaluate(member.getRank(), member.getJoinDate(), xp, today);

			PromotionStatus status = evaluation.getStatus();
			String detail = null;
			if (entry == null)
			{
				status = status == PromotionStatus.UNKNOWN_RANK || status == PromotionStatus.MAX_RANK ? status : PromotionStatus.PENDING;
			}
			else
			{
				checked++;
				if (xp == null)
				{
					failed++;
					detail = entry.getError();
					status = status == PromotionStatus.UNKNOWN_RANK || status == PromotionStatus.MAX_RANK ? status : PromotionStatus.FAILED;
				}
			}

			if (status == PromotionStatus.READY)
			{
				String next = evaluation.getNextRank();
				if (ignoredPlayers.contains(XpCache.key(member.getName())) || ignoredTargets.contains(next.toLowerCase(Locale.ROOT)))
				{
					status = PromotionStatus.IGNORED;
				}
				else
				{
					found.add(new Candidate(member.getName(), member.getRank(), next));
					targets.put(XpCache.key(member.getName()), next);
					currentRanks.put(XpCache.key(member.getName()), member.getRank());
				}
			}

			allRows.add(new PromotionRow(member.getName(), member.getRank(), evaluation.getNextRank(),
				evaluation.getMonths(), evaluation.getRequiredMonths(), evaluation.getRequiredXpMillions(), xp, status, detail, member.getRankIcon(),
				entry == null ? 0 : entry.getFetchedAt()));
		}

		found.sort(Comparator.comparing(Candidate::getName, String.CASE_INSENSITIVE_ORDER));
		candidates = Collections.unmodifiableList(found);
		candidateTargets = Collections.unmodifiableMap(targets);
		candidateCurrentRanks = Collections.unmodifiableMap(currentRanks);
		rows = Collections.unmodifiableList(allRows);
		checkedCount = checked;
		failedCount = failed;
		checkableCount = checkable;
		promotionPanel.update(rows, summary());
	}

	private PromotionPanel.Summary summary()
	{
		return new PromotionPanel.Summary(members.size(), candidates.size(), checkedCount, checkableCount, failedCount,
			config.enableWiseOldMan(), fetching, groupStatus, refreshAvailableAt());
	}

	private long refreshAvailableAt()
	{
		return lastManualRefresh + MANUAL_REFRESH_COOLDOWN_MS;
	}

	private static Set<String> splitList(String value, boolean names)
	{
		Set<String> result = new HashSet<>();
		if (value == null)
		{
			return result;
		}

		for (String part : value.split(","))
		{
			String trimmed = part.trim();
			if (!trimmed.isEmpty())
			{
				result.add(names ? XpCache.key(trimmed) : trimmed.toLowerCase(Locale.ROOT));
			}
		}
		return result;
	}
}
