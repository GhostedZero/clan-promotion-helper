# Clan Promotion Helper

A RuneLite plugin that helps clan leaders identify members who are ready for promotion. It compares each member's time in the clan and XP gained against your clan's rank requirements, then lists eligible members in a sidebar and on-screen panel and highlights them in the Clan Settings member list.

<img src="screenshots/ready.png" width="225" alt="The Ready tab of the sidebar showing example clan members and their promotion progress">

## Features

- Automatically reads clan members, ranks and join dates from the game.

- Tracks XP gained using optional [wiseoldman.net](https://wiseoldman.net) lookups.

- Highlights members who meet the requirements for their next rank.

- Supports custom rank ladders to match your clan's promotion system, plus vanity ranks based on levels and kill counts, with an editor in the sidebar.

- Caches results locally to make subsequent checks faster.

## Setup

In the plugin's settings:

1. **Look up XP on Wise Old Man:** turn this on. It's off by default because it sends requests to a third-party site.
2. **Contact name:** your Discord name. It's sent with each lookup so Wise Old Man can reach you if needed about your usage.
3. **API key** (optional): raises Wise Old Man's limit from 20 to 100 lookups a minute. Ask for one on the [Wise Old Man Discord](https://wiseoldman.net/discord).
4. **Rank requirements:** check the table below matches your clan.
5. **Wise Old Man group ID** (optional): if your clan has a [group](https://wiseoldman.net/groups), enter its ID (the number at the end of its web address). After the first fill, a refresh then takes one request for the whole group instead of one per member. If the group request fails, the plugin falls back to looking members up one by one.

A clan of about 500 members takes around 28 minutes to check without an API key and about 6 minutes with one. Results are saved per account and only refreshed after the refresh interval (6 hours by default), so later logins are quick.

## Sidebar

Click the plugin's icon in RuneLite's sidebar:

- Tabs for **Ready**, **Later** (not ready yet), **Failed** and **All**, plus a name search.
- The list of members is limited to 200 results so you may need to use the search function to find specific members in larger clans.
- Each member shows their rank, months in the clan, XP gained and progress towards the next rank.
- **Refresh now** re-checks XP straight away. With a group ID set it refreshes everyone with one request; without one it only retries members with no result or a failed lookup.

## Rank requirements

One rank per line, lowest first, as `Rank:months:XP in millions`, optionally followed by `:boss kills` and `:AND` or `:OR`. This is an example ladder; replace it with your clan's own rank titles and requirements:

```
Thief:0:0
Recruit:1:1
Corporal:2:2
Sergeant:3:3
Lieutenant:4:4
Captain:5:5
General:6:8
Officer:9:12
Commander:12:20
Colonel:15:25
Brigadier:17:30
Admiral:20:50
Marshal:24:75
```

If a title doesn't match, those members show "Rank not in table" and are never suggested. Leave out ranks you don't promote to, such as Owner.

In **Edit rules**, **Fill from clan** lists your clan's ranks in their in-game order, so you only fill in the requirements. Ranks you already set keep their requirements. Guest, administrator and higher, and your vanity ranks are left out, and ladder ranks your clan doesn't have are removed. Nothing changes until you press Save.

### Boss kills

Add boss kills (all bosses together) and how they combine with XP:

```
Sergeant:3:3:25:OR     3 months, and 3M XP or 25 boss kills
Captain:5:5:50:AND     5 months, and 5M XP and 50 boss kills
```

- `AND` (the default) needs both, `OR` needs either. The months are always required.
- A `0` isn't required, so `General:6:8:0:OR` still needs the 8M XP.
- Boss kills only come from one-by-one lookups, so members whose next rank needs them aren't covered by the group refresh.
- A failed lookup is never treated as 0 kills.

Lines the plugin can't read are skipped and listed in the sidebar (hover the note under the tiles).

### Vanity ranks

Side ranks members can qualify for from any rank, checked on lifetime stats instead of gains. Use **Edit rules** in the sidebar, or the **Vanity ranks** setting with one rank per line as `Name: condition; condition` (every condition is needed):

```
PvM Legend: rank Sergeant or 6 months; level slayer 99; kills all 12500; kills raids 50
Maxed Legend: rank Sergeant or 6 months; level all except sailing 99
PvM GOD: rank Sergeant or 6 months; kills TzKal-Zuk, Sol Heredit 1; kills all 5000
```

- `rank Sergeant or 6 months`: holds Sergeant or any higher rank (by the in-game rank order), has been seen holding one, or joined at least 6 months ago. Either half can be left out.
- `level slayer 99`, `level all 99`, `level all except sailing 99`.
- `kills all N` counts every boss on the hiscores, including Wintertodt, Tempoross and Zalcano. `kills raids N` counts every Chambers, Theatre and Tombs mode on the hiscores. Boss names can also be listed with commas.
- Kill counts below the hiscores' minimum for a boss show as unranked and can't be counted, so totals can read a little low.
- Ready members show the vanity rank as their next rank. Members already holding it aren't suggested it again.
- Vanity ranks need each member looked up one by one, so the group refresh no longer saves requests for members who could still earn one.

### Counting from the last rank change

Turn on **Count from last rank change** to count months, XP and boss kills from a member's last rank change instead of their join date. The game doesn't record when ranks change, so the plugin notes it itself whenever it sees a member's rank differ from last time:

- Only changes seen while you're logged in with the plugin count; a change made while you were offline is dated to when you next log in.
- Members it has never seen change rank are counted from their join date.
- Changes are recorded even while the setting is off, so turning it on later uses them.

## Privacy

Wise Old Man lookups are **off by default**. When you turn them on, the plugin sends only these things, and only to [wiseoldman.net](https://wiseoldman.net):

- each clan member's **name** and **join date** (or last rank change date, if you count from that), so Wise Old Man can say how much XP and boss kills they gained since then;
- your **contact name** and **API key**, if you entered them.

Like any web request, this shows your IP address to Wise Old Man. Results are saved only on your own computer, and the plugin never changes anything on Wise Old Man: it only reads.

## Notes

- A failed lookup, for example for a player Wise Old Man doesn't track, is never treated as 0 XP. It shows under Failed and is retried after the refresh interval.
- XP gained only counts from when Wise Old Man first tracked the player, so members who weren't tracked before joining may show less than they really gained.

## Credits

The member-list highlighting follows the approach of the [Clan Rank Helper](https://github.com/homer2011/clan-rank-helper) plugin (BSD-2).
