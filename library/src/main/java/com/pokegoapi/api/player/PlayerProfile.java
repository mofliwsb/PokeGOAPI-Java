/*
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.pokegoapi.api.player;

import POGOProtos.Data.Player.CurrencyOuterClass;
import POGOProtos.Data.Player.EquippedBadgeOuterClass.EquippedBadge;
import POGOProtos.Data.Player.PlayerStatsOuterClass;
import POGOProtos.Data.PlayerDataOuterClass.PlayerData;
import POGOProtos.Enums.GenderOuterClass.Gender;
import POGOProtos.Enums.TutorialStateOuterClass;
import POGOProtos.Inventory.Item.ItemAwardOuterClass.ItemAward;
import POGOProtos.Networking.Requests.Messages.CheckAwardedBadgesMessageOuterClass.CheckAwardedBadgesMessage;
import POGOProtos.Networking.Requests.Messages.ClaimCodenameMessageOuterClass.ClaimCodenameMessage;
import POGOProtos.Networking.Requests.Messages.EncounterTutorialCompleteMessageOuterClass.EncounterTutorialCompleteMessage;
import POGOProtos.Networking.Requests.Messages.EquipBadgeMessageOuterClass.EquipBadgeMessage;
import POGOProtos.Networking.Requests.Messages.GetPlayerMessageOuterClass.GetPlayerMessage;
import POGOProtos.Networking.Requests.Messages.LevelUpRewardsMessageOuterClass.LevelUpRewardsMessage;
import POGOProtos.Networking.Requests.Messages.MarkTutorialCompleteMessageOuterClass.MarkTutorialCompleteMessage;
import POGOProtos.Networking.Requests.Messages.SetAvatarMessageOuterClass.SetAvatarMessage;
import POGOProtos.Networking.Requests.RequestTypeOuterClass.RequestType;
import POGOProtos.Networking.Responses.CheckAwardedBadgesResponseOuterClass.CheckAwardedBadgesResponse;
import POGOProtos.Networking.Responses.ClaimCodenameResponseOuterClass.ClaimCodenameResponse;
import POGOProtos.Networking.Responses.DownloadSettingsResponseOuterClass.DownloadSettingsResponse;
import POGOProtos.Networking.Responses.EquipBadgeResponseOuterClass;
import POGOProtos.Networking.Responses.GetInventoryResponseOuterClass.GetInventoryResponse;
import POGOProtos.Networking.Responses.GetPlayerResponseOuterClass.GetPlayerResponse;
import POGOProtos.Networking.Responses.LevelUpRewardsResponseOuterClass.LevelUpRewardsResponse;
import POGOProtos.Networking.Responses.MarkTutorialCompleteResponseOuterClass.MarkTutorialCompleteResponse;
import POGOProtos.Networking.Responses.SetAvatarResponseOuterClass.SetAvatarResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.inventory.Item;
import com.pokegoapi.api.inventory.ItemBag;
import com.pokegoapi.api.inventory.Stats;
import com.pokegoapi.api.listener.TutorialListener;
import com.pokegoapi.api.pokemon.StarterPokemon;
import com.pokegoapi.exceptions.InvalidCurrencyException;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;
import com.pokegoapi.main.CommonRequests;
import com.pokegoapi.main.ServerRequest;
import com.pokegoapi.util.Log;
import lombok.Setter;

import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class PlayerProfile {
	private static final String TAG = PlayerProfile.class.getSimpleName();
	private final PokemonGo api;
	private final PlayerLocale playerLocale;
	private PlayerData playerData;
	private EquippedBadge badge;
	private PlayerAvatar avatar;
	private DailyBonus dailyBonus;
	private ContactSettings contactSettings;
	private Map<Currency, Integer> currencies = new EnumMap<>(Currency.class);
	@Setter
	private Stats stats;
	private TutorialState tutorialState;

	/**
	 * @param api the api
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 */
	public PlayerProfile(PokemonGo api) throws LoginFailedException, RemoteServerException {
		this.api = api;
		this.playerLocale = new PlayerLocale();

		if (playerData == null) {
			updateProfile();
		}
	}

	/**
	 * Updates the player profile with the latest data.
	 *
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 */
	public void updateProfile() throws RemoteServerException, LoginFailedException {
		GetPlayerMessage getPlayerReqMsg = GetPlayerMessage.newBuilder()
				.setPlayerLocale(playerLocale.getPlayerLocale())
				.build();

		ServerRequest getPlayerServerRequest = new ServerRequest(RequestType.GET_PLAYER, getPlayerReqMsg);
		api.getRequestHandler().sendServerRequests(
				CommonRequests.appendCheckChallenge(api, getPlayerServerRequest));

		try {
			updateProfile(GetPlayerResponse.parseFrom(getPlayerServerRequest.getData()));
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}
	}

	/**
	 * Update the profile with the given response
	 *
	 * @param playerResponse the response
	 */
	public void updateProfile(GetPlayerResponse playerResponse) {
		updateProfile(playerResponse.getPlayerData());
	}

	/**
	 * Update the profile with the given player data
	 *
	 * @param playerData the data for update
	 */
	public void updateProfile(PlayerData playerData) {
		this.playerData = playerData;

		avatar = new PlayerAvatar(playerData.getAvatar());
		dailyBonus = new DailyBonus(playerData.getDailyBonus());
		contactSettings = new ContactSettings(playerData.getContactSettings());

		// maybe something more graceful?
		for (CurrencyOuterClass.Currency currency : playerData.getCurrenciesList()) {
			try {
				addCurrency(currency.getName(), currency.getAmount());
			} catch (InvalidCurrencyException e) {
				Log.w(TAG, "Error adding currency. You can probably ignore this.", e);
			}
		}

		// Tutorial state
		tutorialState = new TutorialState(playerData.getTutorialStateList());
	}

	/**
	 * Accept the rewards granted and the items unlocked by gaining a trainer level up. Rewards are retained by the
	 * server until a player actively accepts them.
	 * The rewarded items are automatically inserted into the players item bag.
	 *
	 * @param level the trainer level that you want to accept the rewards for
	 * @return a PlayerLevelUpRewards object containing information about the items rewarded and unlocked for this level
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 * @see PlayerLevelUpRewards
	 */
	public PlayerLevelUpRewards acceptLevelUpRewards(int level) throws RemoteServerException, LoginFailedException {
		// Check if we even have achieved this level yet
		if (level > stats.getLevel()) {
			return new PlayerLevelUpRewards(PlayerLevelUpRewards.Status.NOT_UNLOCKED_YET);
		}
		LevelUpRewardsMessage msg = LevelUpRewardsMessage.newBuilder()
				.setLevel(level)
				.build();
		ServerRequest serverRequest = new ServerRequest(RequestType.LEVEL_UP_REWARDS, msg);
		api.getRequestHandler().sendServerRequests(serverRequest);
		LevelUpRewardsResponse response;
		try {
			response = LevelUpRewardsResponse.parseFrom(serverRequest.getData());
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}
		// Add the awarded items to our bag
		ItemBag bag = api.getInventories().getItemBag();
		for (ItemAward itemAward : response.getItemsAwardedList()) {
			Item item = bag.getItem(itemAward.getItemId());
			item.setCount(item.getCount() + itemAward.getItemCount());
		}
		// Build a new rewards object and return it
		return new PlayerLevelUpRewards(response);
	}

	/**
	 * Add currency.
	 *
	 * @param name   the name
	 * @param amount the amount
	 * @throws InvalidCurrencyException the invalid currency exception
	 */
	public void addCurrency(String name, int amount) throws InvalidCurrencyException {
		try {
			currencies.put(Currency.valueOf(name), amount);
		} catch (Exception e) {
			throw new InvalidCurrencyException();
		}
	}

	/**
	 * Check and equip badges.
	 *
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException When a buffer exception is thrown
	 */
	public void checkAndEquipBadges() throws LoginFailedException, RemoteServerException {
		CheckAwardedBadgesMessage msg = CheckAwardedBadgesMessage.newBuilder().build();
		ServerRequest serverRequest = new ServerRequest(RequestType.CHECK_AWARDED_BADGES, msg);
		api.getRequestHandler().sendServerRequests(serverRequest);
		CheckAwardedBadgesResponse response;
		try {
			response = CheckAwardedBadgesResponse.parseFrom(serverRequest.getData());
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}
		if (response.getSuccess()) {
			for (int i = 0; i < response.getAwardedBadgesCount(); i++) {
				EquipBadgeMessage msg1 = EquipBadgeMessage.newBuilder()
						.setBadgeType(response.getAwardedBadges(i))
						.setBadgeTypeValue(response.getAwardedBadgeLevels(i)).build();
				ServerRequest serverRequest1 = new ServerRequest(RequestType.EQUIP_BADGE, msg1);
				api.getRequestHandler().sendServerRequests(serverRequest1);
				EquipBadgeResponseOuterClass.EquipBadgeResponse response1;
				try {
					response1 = EquipBadgeResponseOuterClass.EquipBadgeResponse.parseFrom(serverRequest1.getData());
					badge = response1.getEquipped();
				} catch (InvalidProtocolBufferException e) {
					throw new RemoteServerException(e);
				}
			}
		}
	}

	/**
	 * Gets currency.
	 *
	 * @param currency the currency
	 * @return the currency
	 */
	public int getCurrency(Currency currency) {
		return currencies.get(currency);
	}

	public enum Currency {
		STARDUST, POKECOIN;
	}

	/**
	 * Gets raw player data proto
	 *
	 * @return Player data
	 */
	public PlayerData getPlayerData() {
		return playerData;
	}

	/**
	 * Gets avatar
	 *
	 * @return Player Avatar object
	 */
	public PlayerAvatar getAvatar() {
		return avatar;
	}

	/**
	 * Gets daily bonus
	 *
	 * @return DailyBonus object
	 */
	public DailyBonus getDailyBonus() {
		return dailyBonus;
	}

	/**
	 * Gets contact settings
	 *
	 * @return ContactSettings object
	 */
	public ContactSettings getContactSettings() {
		return contactSettings;
	}

	/**
	 * Gets a map of all currencies
	 *
	 * @return map of currencies
	 */
	public Map<Currency, Integer> getCurrencies() {
		return currencies;
	}

	/**
	 * Gets player stats
	 *
	 * @return stats API objet
	 */
	public Stats getStats() {
		if (stats == null) {
			return new Stats(PlayerStatsOuterClass.PlayerStats.newBuilder().build());
		}
		return stats;
	}

	/**
	 * Gets tutorial states
	 *
	 * @return TutorialState object
	 */
	public TutorialState getTutorialState() {
		return tutorialState;
	}

	/**
	 * Set the account to legal screen in order to receive valid response
	 *
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 */
	public void activateAccount() throws LoginFailedException, RemoteServerException {
		markTutorial(TutorialStateOuterClass.TutorialState.LEGAL_SCREEN);
	}

	/**
	 * Setup an avatar for the current account
	 *
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 */
	public void setupAvatar() throws LoginFailedException, RemoteServerException {
		SecureRandom random = new SecureRandom();

		Gender gender = random.nextInt(100) % 2 == 0 ? Gender.FEMALE : Gender.MALE;
		PlayerAvatar avatar = new PlayerAvatar(gender,
				random.nextInt(PlayerAvatar.getAvailableSkins()),
				random.nextInt(PlayerAvatar.getAvailableHair()),
				random.nextInt(PlayerAvatar.getAvailableShirts(gender)),
				random.nextInt(PlayerAvatar.getAvailablePants(gender)),
				random.nextInt(PlayerAvatar.getAvailableHats()),
				random.nextInt(PlayerAvatar.getAvailableShoes()),
				random.nextInt(PlayerAvatar.getAvailableEyes()),
				random.nextInt(PlayerAvatar.getAvailableBags(gender)));

		List<TutorialListener> listeners = api.getListeners(TutorialListener.class);
		for (TutorialListener listener : listeners) {
			PlayerAvatar listenerAvatar = listener.selectAvatar(api);
			if (listenerAvatar != null) {
				avatar = listenerAvatar;
				break;
			}
		}

		final SetAvatarMessage setAvatarMessage = SetAvatarMessage.newBuilder()
				.setPlayerAvatar(avatar.getAvatar())
				.build();

		ServerRequest[] requests = CommonRequests.fillRequest(
				new ServerRequest(RequestType.SET_AVATAR, setAvatarMessage), api);

		api.getRequestHandler().sendServerRequests(requests);

		try {
			SetAvatarResponse setAvatarResponse = SetAvatarResponse.parseFrom(requests[0].getData());
			playerData = setAvatarResponse.getPlayerData();

			updateProfile(playerData);

			api.getInventories().updateInventories(GetInventoryResponse.parseFrom(requests[2].getData()));
			api.getSettings().updateSettings(DownloadSettingsResponse.parseFrom(requests[4].getData()));
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}

		markTutorial(TutorialStateOuterClass.TutorialState.AVATAR_SELECTION);

		api.fireRequestBlockTwo();
	}

	/**
	 * Encounter tutorial complete. In other words, catch the first Pokémon
	 *
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 */
	public void encounterTutorialComplete() throws LoginFailedException, RemoteServerException {
		StarterPokemon starter = StarterPokemon.random();

		List<TutorialListener> listeners = api.getListeners(TutorialListener.class);
		for (TutorialListener listener : listeners) {
			StarterPokemon pokemon = listener.selectStarter(api);
			if (pokemon != null) {
				starter = pokemon;
				break;
			}
		}

		final EncounterTutorialCompleteMessage.Builder encounterTutorialCompleteBuilder =
				EncounterTutorialCompleteMessage.newBuilder()
				.setPokemonId(starter.getPokemon());

		ServerRequest[] requests = CommonRequests.fillRequest(
				new ServerRequest(RequestType.ENCOUNTER_TUTORIAL_COMPLETE,
				encounterTutorialCompleteBuilder.build()), api);

		api.getRequestHandler().sendServerRequests(requests);

		try {
			api.getInventories().updateInventories(GetInventoryResponse.parseFrom(requests[2].getData()));
			api.getSettings().updateSettings(DownloadSettingsResponse.parseFrom(requests[4].getData()));
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}

		final GetPlayerMessage getPlayerReqMsg = GetPlayerMessage.newBuilder()
				.setPlayerLocale(playerLocale.getPlayerLocale())
				.build();
		requests = CommonRequests.fillRequest(
				new ServerRequest(RequestType.GET_PLAYER, getPlayerReqMsg), api);

		api.getRequestHandler().sendServerRequests(requests);

		try {
			updateProfile(GetPlayerResponse.parseFrom(requests[0].getData()));

			api.getInventories().updateInventories(GetInventoryResponse.parseFrom(requests[2].getData()));
			api.getSettings().updateSettings(DownloadSettingsResponse.parseFrom(requests[4].getData()));
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}
	}

	/**
	 * Setup an user name for our account
	 *
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 */
	public void claimCodeName() throws LoginFailedException, RemoteServerException {
		claimCodeName(null);
	}

	/**
	 * Setup an user name for our account
	 *
	 * @param lastFailure the last name used that was already taken; null for first try.
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 */
	public void claimCodeName(String lastFailure) throws LoginFailedException, RemoteServerException {
		String name = randomCodenameGenerator();

		List<TutorialListener> listeners = api.getListeners(TutorialListener.class);
		for (TutorialListener listener : listeners) {
			String listenerName = listener.claimName(api, lastFailure);
			if (listenerName != null) {
				name = listenerName;
				break;
			}
		}

		ClaimCodenameMessage claimCodenameMessage = ClaimCodenameMessage.newBuilder()
				.setCodename(name)
				.build();

		ServerRequest[] requests = CommonRequests.fillRequest(
				new ServerRequest(RequestType.CLAIM_CODENAME,
						claimCodenameMessage), api);

		api.getRequestHandler().sendServerRequests(requests);

		String updatedCodename = null;
		try {
			api.getInventories().updateInventories(GetInventoryResponse.parseFrom(requests[2].getData()));
			api.getSettings().updateSettings(DownloadSettingsResponse.parseFrom(requests[4].getData()));

			ClaimCodenameResponse claimCodenameResponse = ClaimCodenameResponse.parseFrom(requests[0].getData());
			if (claimCodenameResponse.getStatus() != ClaimCodenameResponse.Status.SUCCESS) {
				if (claimCodenameResponse.getUpdatedPlayer().getRemainingCodenameClaims() > 0) {
					claimCodeName(name);
				}
			} else {
				updatedCodename = claimCodenameResponse.getCodename();
				updateProfile(claimCodenameResponse.getUpdatedPlayer());
			}
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}

		if (updatedCodename != null) {
			markTutorial(TutorialStateOuterClass.TutorialState.NAME_SELECTION);

			final GetPlayerMessage getPlayerReqMsg = GetPlayerMessage.newBuilder()
					.setPlayerLocale(playerLocale.getPlayerLocale())
					.build();
			requests = CommonRequests.fillRequest(
					new ServerRequest(RequestType.GET_PLAYER, getPlayerReqMsg), api);

			api.getRequestHandler().sendServerRequests(requests);

			try {
				updateProfile(GetPlayerResponse.parseFrom(requests[0].getData()));

				api.getInventories().updateInventories(GetInventoryResponse.parseFrom(requests[2].getData()));
				api.getSettings().updateSettings(DownloadSettingsResponse.parseFrom(requests[4].getData()));
			} catch (InvalidProtocolBufferException e) {
				throw new RemoteServerException(e);
			}
		}
	}

	/**
	 * The last step, mark the last tutorial state as completed
	 *
	 * @throws LoginFailedException  when the auth is invalid
	 * @throws RemoteServerException when the server is down/having issues
	 */
	public void firstTimeExperienceComplete()
			throws LoginFailedException, RemoteServerException {
		markTutorial(TutorialStateOuterClass.TutorialState.FIRST_TIME_EXPERIENCE_COMPLETE);
	}

	private void markTutorial(TutorialStateOuterClass.TutorialState state)
				throws LoginFailedException, RemoteServerException {
		final MarkTutorialCompleteMessage tutorialMessage = MarkTutorialCompleteMessage.newBuilder()
				.addTutorialsCompleted(state)
				.setSendMarketingEmails(false)
				.setSendPushNotifications(false).build();

		ServerRequest[] requests = CommonRequests.fillRequest(
				new ServerRequest(RequestType.MARK_TUTORIAL_COMPLETE, tutorialMessage), api);

		api.getRequestHandler().sendServerRequests(requests);

		try {
			playerData = MarkTutorialCompleteResponse.parseFrom(requests[0].getData()).getPlayerData();

			updateProfile(playerData);

			api.getInventories().updateInventories(GetInventoryResponse.parseFrom(requests[2].getData()));
			api.getSettings().updateSettings(DownloadSettingsResponse.parseFrom(requests[4].getData()));
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}
	}

	private static String randomCodenameGenerator() {
		final String a = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
		final SecureRandom r = new SecureRandom();
		final int l = new Random().nextInt(15 - 10) + 10;
		StringBuilder sb = new StringBuilder(l);
		for (int i = 0;i < l;i++) {
			sb.append(a.charAt(r.nextInt(a.length())));
		}
		return sb.toString();
	}
}