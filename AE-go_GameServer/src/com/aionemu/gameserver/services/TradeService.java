/*
 * This file is part of aion-unique <aion-unique.org>.
 *
 *  aion-unique is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aion-unique is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aion-unique.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import com.aionemu.gameserver.dataholders.GoodsListData;
import com.aionemu.gameserver.dataholders.TradeListData;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Inventory;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.TradeListTemplate;
import com.aionemu.gameserver.model.templates.TradeListTemplate.TradeTab;
import com.aionemu.gameserver.model.templates.goods.GoodsList;
import com.aionemu.gameserver.model.trade.TradeItem;
import com.aionemu.gameserver.model.trade.TradeList;
import com.aionemu.gameserver.network.aion.serverpackets.SM_DELETE_ITEM;
import com.aionemu.gameserver.network.aion.serverpackets.SM_INVENTORY_UPDATE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_UPDATE_ITEM;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;
import com.google.inject.Inject;

/**
 * @author ATracer
 *
 */
public class TradeService
{
	private static final Logger log = Logger.getLogger(TradeService.class);
	
	@Inject
	private ItemService itemService;
	@Inject
	private World world;
	@Inject
	private TradeListData tradeListData;
	@Inject
	private GoodsListData goodsListData;

	/**
	 * 
	 * @param player
	 * @param tradeList
	 * @return
	 */
	public boolean performBuyFromShop(Player player, TradeList tradeList)
	{
		
		if(!validateBuyItems(tradeList))
		{
			PacketSendUtility.sendMessage(player, "Some items are not allowed to be selled from this npc");
			return false;
		}
		
		Inventory inventory  = player.getInventory();
		Item kinahItem = inventory.getKinahItem();

		int freeSlots = inventory.getLimit() - inventory.getUnquippedItems().size() + 1;
		//1. check kinah
		int kinahCount = kinahItem.getItemCount();
		int tradeListPrice = tradeList.calculateBuyListPrice() * 2;
		if(kinahCount < tradeListPrice)
			return false; //ban :)

		//2. check free slots, need to check retail behaviour
		if(freeSlots < tradeList.size())
			return false; //TODO message

		List<Item> addedItems = new ArrayList<Item>();
		for(TradeItem tradeItem : tradeList.getTradeItems())
		{
			int count = itemService.addItem(player, tradeItem.getItemTemplate().getItemId(), tradeItem.getCount(), false); // addToBag is old and have alot of bugs with item adding, suggest to remove it.
			if(count != 0)
			{
				log.warn(String.format("CHECKPOINT: itemservice couldnt add all items on buy: %d %d %d %d", player.getObjectId(), 
					tradeItem.getItemTemplate().getItemId(), tradeItem.getCount(), count));
				kinahItem.decreaseItemCount(tradeListPrice);
				return false;
			}		
		}
		kinahItem.decreaseItemCount(tradeListPrice);
		PacketSendUtility.sendPacket(player, new SM_UPDATE_ITEM(kinahItem));
		PacketSendUtility.sendPacket(player, new SM_INVENTORY_UPDATE(addedItems));
		//TODO message
		return true;
	}

	/**
	 * @param tradeList
	 */
	private boolean validateBuyItems(TradeList tradeList)
	{
		Npc npc = (Npc) world.findAionObject(tradeList.getNpcObjId());
		TradeListTemplate tradeListTemplate = tradeListData.getTradeListTemplate(npc.getTemplate().getTemplateId());

		Set<Integer> allowedItems = new HashSet<Integer>();
		for(TradeTab tradeTab : tradeListTemplate.getTradeTablist())
		{
			GoodsList goodsList = goodsListData.getGoodsListById(tradeTab.getId());
			if(goodsList != null && goodsList.getItemIdList() != null)
			{
				allowedItems.addAll(goodsList.getItemIdList());
			}				
		}
		
		for(TradeItem tradeItem : tradeList.getTradeItems())
		{
			if(!allowedItems.contains(tradeItem.getItemId()))
				return false;
		}
		return true;
	}

	/**
	 * 
	 * @param player
	 * @param tradeList
	 * @return
	 */
	public boolean performSellToShop(Player player, TradeList tradeList)
	{
		Inventory inventory = player.getInventory();

		int kinahReward = 0;		
		for(TradeItem tradeItem : tradeList.getTradeItems())
		{
			Item item = inventory.getItemByObjId(tradeItem.getItemId());
			// 1) don't allow to sell fake items;
			if(item == null)
				return false;

			if(item.getItemCount() - tradeItem.getCount() == 0)
			{
				inventory.removeFromBag(item);    // need to be here to avoid exploit by sending packet with many items with same unique ids
				kinahReward += item.getItemTemplate().getPrice() * item.getItemCount();
				//TODO check retail packet here
				PacketSendUtility.sendPacket(player, new SM_DELETE_ITEM(item.getObjectId()));
			}
			else if(item.getItemCount() - tradeItem.getCount() > 0)
			{
				if(item.decreaseItemCount(tradeItem.getCount()))
				{
					//TODO check retail packet here
					kinahReward += item.getItemTemplate().getPrice() * tradeItem.getCount();
					PacketSendUtility.sendPacket(player, new SM_UPDATE_ITEM(item));
				}
				else return false;
			}
			else return false;
		}

		Item kinahItem = inventory.getKinahItem();
		kinahItem.increaseItemCount(kinahReward / 2);
		PacketSendUtility.sendPacket(player, new SM_UPDATE_ITEM(kinahItem));

		return true;
	}
}
