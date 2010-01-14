/*
 * This file is part of aion-unique <aion-unique.com>.
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
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;

import com.aionemu.commons.database.dao.DAOManager;
import com.aionemu.gameserver.dao.ItemStoneListDAO;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Inventory;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.stats.listeners.ItemEquipmentListener;
import com.aionemu.gameserver.model.items.ItemId;
import com.aionemu.gameserver.model.items.ItemStone;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.network.aion.serverpackets.SM_DELETE_ITEM;
import com.aionemu.gameserver.network.aion.serverpackets.SM_INVENTORY_UPDATE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_UPDATE_ITEM;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.idfactory.IDFactory;
import com.aionemu.gameserver.utils.idfactory.IDFactoryAionObject;
import com.aionemu.gameserver.world.World;
import com.google.inject.Inject;

/**
 * @author ATracer
 *	This class is used for Item manipulations (creation, disposing, modification)
 *	Can be used as a factory for Item objects
 */
public class ItemService
{ 
	private static Logger log = Logger.getLogger(ItemService.class);

	private IDFactory aionObjectsIDFactory;
	private World world;

	@Inject
	public ItemService(@IDFactoryAionObject IDFactory aionObjectsIDFactory, World world)
	{
		this.aionObjectsIDFactory = aionObjectsIDFactory;
		this.world = world;
	}

	/**
	 * @param itemId
	 * @param count
	 * @return
	 * 
	 * Creates new Item instance.
	 * If count is greater than template maxStackCount, count value will be cut to maximum allowed
	 * This method will return null if ItemTemplate for itemId was not found.
	 */
	public Item newItem(int itemId, int count)
	{
		ItemTemplate itemTemplate = DataManager.ITEM_DATA.getItemTemplate(itemId);
		if(itemTemplate == null)
		{
			log.error("Item was not populated correctly. Item template is missing for item id: " + itemId);
			return null;
		}

		int maxStackCount = itemTemplate.getMaxStackCount();	
		if(count > maxStackCount && maxStackCount != 0)
		{
			count = maxStackCount;
		}

		//TODO if Item object will contain ownerId - item can be saved to DB before return
		return new Item(aionObjectsIDFactory.nextId(), itemTemplate, count, false, 0);
	}

	/**
	 * @param itemId
	 * @param itemUniqueId
	 * @param count
	 * @param isEquipped
	 * @param slot
	 * @return
	 * 
	 *  Loads Item instance with specified itemUniqueId
	 */
	public Item loadItem(int itemId, int itemUniqueId, int count, boolean isEquipped, int slot)
	{
		ItemTemplate itemTemplate = DataManager.ITEM_DATA.getItemTemplate(itemId);
		if(itemTemplate == null)
		{
			log.error("Item was not populated correctly. Item template is missing for item id: " + itemId);
		}

		return new Item(itemUniqueId, itemTemplate, count, isEquipped, slot);
	}       

	/**
	 *  Loads item stones from DB for each item in a list if item is ARMOR or WEAPON
	 *  
	 * @param itemList
	 */
	public void loadItemStones(Player player)
	{
		List<Item> itemList = player.getInventory().getAllItems();

		for(Item item : itemList)
		{
			if(item.getItemTemplate().isArmor() || item.getItemTemplate().isWeapon())
			{
				item.setItemStones(DAOManager.getDAO(ItemStoneListDAO.class).load(item.getObjectId()));
				//if item equipped - apply stats of item stone
				if(item.isEquipped())
				{
					for(ItemStone itemStone : item.getItemStones())
					{
						ItemEquipmentListener.addStoneStats(itemStone, player.getGameStats());
					}
				}			
			}
		}
	}

	/**
	 *  Used to split item into 2 items
	 *  
	 * @param player
	 * @param itemObjId
	 * @param splitAmount
	 * @param slotNum
	 */
	public void splitItem (Player player, int itemObjId, int splitAmount, int slotNum)
	{
		Inventory inventory = player.getInventory();

		Item itemToSplit = inventory.getItemByObjId(itemObjId);
		if(itemToSplit == null)
		{
			log.warn(String.format("CHECKPOINT: attempt to split null item %d %d %d", itemObjId, splitAmount, slotNum));
			return;
		}

		int oldItemCount = itemToSplit.getItemCount() - splitAmount;

		if(itemToSplit.getItemCount()<splitAmount || oldItemCount == 0)
			return;



		Item newItem = this.newItem(itemToSplit.getItemTemplate().getItemId(), splitAmount);

		if(inventory.putToBag(newItem) != null)
		{
			itemToSplit.decreaseItemCount(splitAmount);

			List<Item> itemsToUpdate = new ArrayList<Item>();
			itemsToUpdate.add(newItem);

			PacketSendUtility.sendPacket(player, new SM_INVENTORY_UPDATE(itemsToUpdate));
			PacketSendUtility.sendPacket(player, new SM_UPDATE_ITEM(itemToSplit));
		}		
		else
		{
			releaseItemId(newItem);
		}
	}
	
	/**
	 *  Used to merge 2 items in inventory
	 *  
	 * @param player
	 * @param sourceItemObjId
	 * @param itemAmount
	 * @param destinationObjId
	 */
	public void mergeItems (Player player, int sourceItemObjId, int itemAmount, int destinationObjId)
	{
		if(itemAmount == 0)
			return;

		Inventory inventory = player.getInventory();

		Item sourceItem = inventory.getItemByObjId(sourceItemObjId);
		Item destinationItem = inventory.getItemByObjId(destinationObjId);
		
		if(sourceItem == null || destinationItem == null)
			return; //Invalid object id provided
		
		if(sourceItem.getItemTemplate().getItemId() != destinationItem.getItemTemplate().getItemId())
			return; //Invalid item type
		
		if(sourceItem.getItemCount() < itemAmount)
			return; //Invalid item amount

		if(sourceItem.getItemCount() == itemAmount)
		{
			destinationItem.increaseItemCount(itemAmount);
			inventory.removeFromBag(sourceItem);

			PacketSendUtility.sendPacket(player, new SM_DELETE_ITEM(sourceItem.getObjectId()));
			PacketSendUtility.sendPacket(player, new SM_UPDATE_ITEM(destinationItem));
		}
		else if(sourceItem.getItemCount() > itemAmount)
		{
			sourceItem.decreaseItemCount(itemAmount);
			destinationItem.increaseItemCount(itemAmount);

			PacketSendUtility.sendPacket(player, new SM_UPDATE_ITEM(sourceItem));
			PacketSendUtility.sendPacket(player, new SM_UPDATE_ITEM(destinationItem));
		}
		else return; // cant happen in theory, but...
	}

	/**
	 *  Adds item count to player inventory
	 *  I moved this method to service cause right implementation of it is critical to server
	 *  operation and could cause starvation of object ids.
	 *  
	 *  This packet will send necessary packets to client (initialize used only from quest engine
	 *  
	 * @param player
	 * @param itemId
	 * @param count - amount of item that were not added to player's inventory
	 */
	public int addItem(Player player, int itemId, int count, boolean isQuestItem)
	{
		Inventory inventory = player.getInventory();

		ItemTemplate itemTemplate =  DataManager.ITEM_DATA.getItemTemplate(itemId);
		if(itemTemplate == null)
			return count;

		int maxStackCount = itemTemplate.getMaxStackCount();

		if (itemId == ItemId.KINAH.value())
		{
			inventory.increaseKinah(count);
			return 0;
		}
		else
		{
			/**
			 * Increase count of existing items
			 */
			List<Item> existingItems = inventory.getAllItemsByItemId(itemId); // look for existing in equipment. need for power shards.
			for(Item existingItem : existingItems)
			{
				if(count == 0)
					break;
				
				int freeCount = maxStackCount - existingItem.getItemCount();
				if(count <= freeCount)
				{
					existingItem.increaseItemCount(count);
					count = 0;
				}
				else
				{
					existingItem.increaseItemCount(freeCount);
					count -= freeCount;
				}
				
				udpateItem(player, existingItem, isQuestItem);
			}

			/**
			 * Create new stacks
			 */

			while(!inventory.isFull() && count > 0)
			{
				// item count still more than maxStack value
				if(count > maxStackCount)
				{
					Item item = newItem(itemId, maxStackCount);
					count -= maxStackCount;
					inventory.putToBag(item);
					udpateItem(player, item, true);
				}
				else
				{
					Item item = newItem(itemId, count);
					inventory.putToBag(item);
					udpateItem(player, item, true);
					count = 0;
				}
			}

			return count;
		}
	}
	
	private void udpateItem(Player player, Item item, boolean useUpdateInventory)
	{
		if(useUpdateInventory)
		{
			PacketSendUtility.sendPacket(player, new SM_INVENTORY_UPDATE(Collections.singletonList(item)));
		}
		else
		{
			PacketSendUtility.sendPacket(player, new SM_UPDATE_ITEM(item));
		}
	}

	/**
	 *  Releases item id if item was not used by caller
	 *  
	 * @param item
	 */
	public void releaseItemId(Item item)
	{
		aionObjectsIDFactory.releaseId(item.getObjectId());
	}
}
