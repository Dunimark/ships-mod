/*******************************************************************************
 * Copyright (c) 2013 jeff.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     jeff - initial API and implementation
 ******************************************************************************/
package cuchaz.ships;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.packet.Packet62LevelSound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import cuchaz.modsShared.Environment;
import cuchaz.modsShared.blocks.BlockMap;
import cuchaz.modsShared.blocks.BlockSet;
import cuchaz.modsShared.blocks.BlockUtils;
import cuchaz.modsShared.blocks.BlockUtils.UpdateRules;
import cuchaz.modsShared.blocks.BoundingBoxInt;
import cuchaz.ships.packets.PacketChangedBlocks;
import cuchaz.ships.packets.PacketShipBlockEvent;

public class ShipWorld extends DetachedWorld
{	
	// NOTE: this member var is essentially cache. It works as long as the client/server are single-threaded
	private ChunkCoordinates m_lookupCoords = new ChunkCoordinates( 0, 0, 0 );
	
	private EntityShip m_ship;
	private BlocksStorage m_storage;
	private BlockMap<TileEntity> m_tileEntities;
	private BlockMap<EntityHanging> m_hangingEntities;
	private BlockSet m_changedBlocks;
	private boolean m_needsRenderUpdate;
	
	public ShipWorld( World world )
	{
		super( world, "Ship" );
		
		// init defaults
		m_ship = null;
		m_storage = new BlocksStorage();
		m_tileEntities = new BlockMap<TileEntity>();
		m_hangingEntities = new BlockMap<EntityHanging>();
		m_changedBlocks = new BlockSet();
	}
	
	public ShipWorld( World world, BlocksStorage storage, BlockMap<TileEntity> tileEntities, BlockMap<EntityHanging> hangingEntities )
	{
		this( world );
		
		// save args
		m_storage = storage;
		m_tileEntities = tileEntities;
		m_hangingEntities = hangingEntities;
		
		// init the tile entities in the world
		for( TileEntity tileEntity : m_tileEntities.values() )
		{
			tileEntity.setWorldObj( this );
			tileEntity.validate();
		}
	}
	
	public ShipWorld( World world, ChunkCoordinates originCoords, BlockSet blocks )
	{
		this( world );
		
		// copy the blocks
		m_storage.readFromWorld( world, originCoords, blocks );
		
		// copy the tile entities
		for( ChunkCoordinates worldCoords : blocks )
		{
			// does this block have a tile entity?
			TileEntity tileEntity = world.getBlockTileEntity( worldCoords.posX, worldCoords.posY, worldCoords.posZ );
			if( tileEntity == null )
			{
				continue;
			}
			
			ChunkCoordinates relativeCoords = new ChunkCoordinates( worldCoords.posX - originCoords.posX, worldCoords.posY - originCoords.posY, worldCoords.posZ - originCoords.posZ );
			
			try
			{
				// copy the tile entity
				NBTTagCompound nbt = new NBTTagCompound();
				tileEntity.writeToNBT( nbt );
				TileEntity tileEntityCopy = TileEntity.createAndLoadEntity( nbt );
				
				// initialize the tile entity
				tileEntityCopy.setWorldObj( this );
				tileEntityCopy.xCoord = relativeCoords.posX;
				tileEntityCopy.yCoord = relativeCoords.posY;
				tileEntityCopy.zCoord = relativeCoords.posZ;
				tileEntityCopy.validate();
				
				// save it to the ship world
				m_tileEntities.put( relativeCoords, tileEntityCopy );
			}
			catch( Exception ex )
			{
				Ships.logger.warning(
					ex,
					"Tile entity %s at (%d,%d,%d) didn't like being moved to the ship. The block was moved, the but tile entity was not moved.",
					tileEntity.getClass().getName(),
					worldCoords.posX, worldCoords.posY, worldCoords.posZ
				);
			}
		}
		
		// copy hanging entities
		for( Map.Entry<ChunkCoordinates,EntityHanging> entry : getNearbyHangingEntities( world, blocks ).entrySet() )
		{
			ChunkCoordinates worldCoords = entry.getKey();
			EntityHanging hangingEntity = entry.getValue();
			
			ChunkCoordinates relativeCoords = new ChunkCoordinates( worldCoords.posX - originCoords.posX, worldCoords.posY - originCoords.posY, worldCoords.posZ - originCoords.posZ );
			
			try
			{
				// copy the hanging entity
				NBTTagCompound nbt = new NBTTagCompound();
				hangingEntity.writeToNBTOptional( nbt );
				EntityHanging hangingEntityCopy = (EntityHanging)EntityList.createEntityFromNBT( nbt, this );
				
				// save it to the ship world
				hangingEntityCopy.xPosition = relativeCoords.posX;
				hangingEntityCopy.yPosition = relativeCoords.posY;
				hangingEntityCopy.zPosition = relativeCoords.posZ;
				hangingEntityCopy.posX -= originCoords.posX;
				hangingEntityCopy.posY -= originCoords.posY;
				hangingEntityCopy.posZ -= originCoords.posZ;
				m_hangingEntities.put( relativeCoords, hangingEntityCopy );
				
				// reset the hanging entity's bounding box after the move
				hangingEntityCopy.setDirection( hangingEntityCopy.hangingDirection );
			}
			catch( Exception ex )
			{
				Ships.logger.warning(
					ex,
					"Hanging entity %s at (%d,%d,%d) didn't like being moved to the ship. The block was moved, the but hanging entity was not moved.",
					hangingEntity.getClass().getName(),
					worldCoords.posX, worldCoords.posY, worldCoords.posZ
				);
			}
		}
	}
	
	public void restoreToWorld( World world, Map<ChunkCoordinates,ChunkCoordinates> correspondence, int waterHeightInBlockSpace )
	{
		// restore the blocks
		m_storage.writeToWorld( world, correspondence );
		
		// bail out the boat if needed (it might have water in the trapped air blocks)
		for( ChunkCoordinates coordsShip : getGeometry().getTrappedAirFromWaterHeight( waterHeightInBlockSpace ) )
		{
			ChunkCoordinates coordsWorld = correspondence.get( coordsShip );
			BlockUtils.removeBlockWithoutNotifyingIt( world, coordsWorld.posX, coordsWorld.posY, coordsWorld.posZ, UpdateRules.UpdateClients );
		}
		
		// restore the tile entities
		for( Map.Entry<ChunkCoordinates,TileEntity> entry : m_tileEntities.entrySet() )
		{
			ChunkCoordinates coordsShip = entry.getKey();
			ChunkCoordinates coordsWorld = correspondence.get( coordsShip );
			TileEntity tileEntity = entry.getValue();
			
			try
			{
				NBTTagCompound nbt = new NBTTagCompound();
				tileEntity.writeToNBT( nbt );
				TileEntity tileEntityCopy = TileEntity.createAndLoadEntity( nbt );
				tileEntityCopy.setWorldObj( world );
				tileEntityCopy.xCoord = coordsWorld.posX;
				tileEntityCopy.yCoord = coordsWorld.posY;
				tileEntityCopy.zCoord = coordsWorld.posZ;
				tileEntityCopy.validate();
				
				world.setBlockTileEntity( coordsWorld.posX, coordsWorld.posY, coordsWorld.posZ, tileEntityCopy );
			}
			catch( Exception ex )
			{
				// remove the tile entity
				world.removeBlockTileEntity( coordsWorld.posX, coordsWorld.posY, coordsWorld.posZ );
				
				Ships.logger.warning(
					ex,
					"Tile entity %s at (%d,%d,%d) didn't like being moved to the world. The tile entity has been removed from its block to prevent further errors.",
					tileEntity.getClass().getName(),
					coordsWorld.posX, coordsWorld.posY, coordsWorld.posZ
				);
			}
		}
		
		// compute the translation from block space to world space
		ChunkCoordinates translation = correspondence.get( new ChunkCoordinates( 0, 0, 0 ) );
		
		// restore hanging entities (on the server only)
		if( !world.isRemote )
		{
			for( Map.Entry<ChunkCoordinates,EntityHanging> entry : m_hangingEntities.entrySet() )
			{
				ChunkCoordinates coordsShip = entry.getKey();
				ChunkCoordinates coordsWorld = correspondence.get( coordsShip );
				EntityHanging hangingEntity = entry.getValue();
				
				try
				{
					// copy the hanging entity
					NBTTagCompound nbt = new NBTTagCompound();
					hangingEntity.writeToNBTOptional( nbt );
					EntityHanging hangingEntityCopy = (EntityHanging)EntityList.createEntityFromNBT( nbt, world );
					
					// save it to the ship world
					hangingEntityCopy.xPosition = coordsWorld.posX;
					hangingEntityCopy.yPosition = coordsWorld.posY;
					hangingEntityCopy.zPosition = coordsWorld.posZ;
					hangingEntityCopy.posX += translation.posX;
					hangingEntityCopy.posY += translation.posY;
					hangingEntityCopy.posZ += translation.posZ;
					
					// reset the hanging entity's bounding box after the move
					hangingEntityCopy.setDirection( hangingEntityCopy.hangingDirection );
					
					// then spawn it
					world.spawnEntityInWorld( hangingEntityCopy );
				}
				catch( Exception ex )
				{
					Ships.logger.warning(
						ex,
						"Hanging entity %s at (%d,%d,%d) didn't like being moved to the world. The block was moved, the but hanging entity was not moved.",
						hangingEntity.getClass().getName(),
						coordsWorld.posX, coordsWorld.posY, coordsWorld.posZ
					);
				}
			}
		}
	}
	
	public BlockMap<EntityHanging> getNearbyHangingEntities( World world, BlockSet blocks )
	{
		// get the bounding box of the blocks
		AxisAlignedBB box = AxisAlignedBB.getBoundingBox( 0, 0, 0, 0, 0, 0 );
		blocks.getBoundingBox().toAxisAlignedBB( box );
		
		// collect the hanging entities that are hanging on a block in the block set
		BlockMap<EntityHanging> out = new BlockMap<EntityHanging>();
		ChunkCoordinates entityBlock = new ChunkCoordinates( 0, 0, 0 );
		@SuppressWarnings( "unchecked" )
		List<EntityHanging> hangingEntities = (List<EntityHanging>)world.getEntitiesWithinAABB( EntityHanging.class, box );
		for( EntityHanging hangingEntity : hangingEntities )
		{
			// is this hanging entity actually on a ship block?
			entityBlock.set( hangingEntity.xPosition, hangingEntity.yPosition, hangingEntity.zPosition );
			if( blocks.contains( entityBlock ) )
			{
				out.put( new ChunkCoordinates( entityBlock ), hangingEntity );
			}
		}
		return out;
	}
	
	public BlocksStorage getBlocksStorage( )
	{
		return m_storage;
	}
	
	public EntityShip getShip( )
	{
		return m_ship;
	}
	
	public void setShip( EntityShip val )
	{
		m_ship = val;
	}
	
	public ShipType getShipType( )
	{
		return ShipType.getByMeta( getBlockMetadata( 0, 0, 0 ) );
	}
	
	public boolean isValid( )
	{
		return m_storage.getNumBlocks() > 0;
	}
	
	public int getNumBlocks( )
	{
		return m_storage.getNumBlocks();
	}
	
	public Set<ChunkCoordinates> coords( )
	{
		return m_storage.coords();
	}
	
	public BlockMap<TileEntity> tileEntities( )
	{
		return m_tileEntities;
	}
	
	public BlockMap<EntityHanging> hangingEntities( )
	{
		return m_hangingEntities;
	}
	
	public ShipGeometry getGeometry( )
	{
		return m_storage.getGeometry();
	}
	
	public BoundingBoxInt getBoundingBox( )
	{
		return m_storage.getGeometry().getEnvelopes().getBoundingBox();
	}
	
	public BlockStorage getBlockStorage( int x, int y, int z )
	{
		m_lookupCoords.set( x, y, z );
		return getBlockStorage( m_lookupCoords );
	}
	
	public BlockStorage getBlockStorage( ChunkCoordinates coords )
	{
		return m_storage.getBlock( coords );
	}
	
	@Override
	public int getBlockId( int x, int y, int z )
	{
		m_lookupCoords.set( x, y, z );
		return getBlockId( m_lookupCoords );
	}
	
	public int getBlockId( ChunkCoordinates coords )
	{
		return getBlockStorage( coords ).id;
	}
	
	@Override
	public TileEntity getBlockTileEntity( int x, int y, int z )
	{
		m_lookupCoords.set( x, y, z );
		return getBlockTileEntity( m_lookupCoords );
	}
	
	public TileEntity getBlockTileEntity( ChunkCoordinates coords )
	{
		return m_tileEntities.get( coords );
	}
	
	@Override
	public int getBlockMetadata( int x, int y, int z )
	{
		m_lookupCoords.set( x, y, z );
		return getBlockMetadata( m_lookupCoords );
	}
	
	public int getBlockMetadata( ChunkCoordinates coords )
	{
		return getBlockStorage( coords ).meta;
	}
	
	@Override
	public boolean setBlock( int x, int y, int z, int newBlockId, int newMeta, int ignored )
	{
		if( applyBlockChange( x, y, z, newBlockId, newMeta ) )
		{
			// on the client do nothing more
			// on the server, buffer the changes to be broadcast to the client
			if( Environment.isServer() )
			{
				m_changedBlocks.add( new ChunkCoordinates( x, y, z ) );
			}
			return true;
		}
		return false;
	}
	
	@Override
	public void setBlockTileEntity( int x, int y, int z, TileEntity tileEntity )
	{
		// do nothing. tile entities are handled differently
	}
	
	@Override
	public boolean setBlockMetadataWithNotify( int x, int y, int z, int meta, int ignored )
	{
		if( applyBlockChange( x, y, z, getBlockId( x, y, z ), meta ) )
		{
			// on the client do nothing more
			// on the server, buffer the changes to be broadcast to the client
			if( Environment.isServer() )
			{
				m_changedBlocks.add( new ChunkCoordinates( x, y, z ) );
			}
			return true;
		}
		return false;
	}
	
	public boolean needsRenderUpdate( )
	{
		boolean val = m_needsRenderUpdate;
		m_needsRenderUpdate = false;
		return val;
	}
	
	public boolean applyBlockChange( int x, int y, int z, int newBlockId, int newMeta )
	{
		m_lookupCoords.set( x, y, z );
		return applyBlockChange( m_lookupCoords, newBlockId, newMeta );
	}
	
	public boolean applyBlockChange( ChunkCoordinates coords, int newBlockId, int newMeta )
	{
		// lookup the affected block
		BlockStorage storage = getBlockStorage( coords );
		int oldBlockId = storage.id;
		
		// only allow benign changes to blocks
		boolean isAllowed = false
			// allow metadata changes
			|| ( oldBlockId == newBlockId )
			// allow furnace block changes
			|| ( oldBlockId == Block.furnaceBurning.blockID && newBlockId == Block.furnaceIdle.blockID )
			|| ( oldBlockId == Block.furnaceIdle.blockID && newBlockId == Block.furnaceBurning.blockID );
		
		if( isAllowed )
		{
			// apply the change
			storage.id = newBlockId;
			storage.meta = newMeta;
			
			// notify the tile entity if needed
			TileEntity tileEntity = getBlockTileEntity( coords );
			if( tileEntity != null )
			{
				tileEntity.updateContainingBlockInfo();
			}
			
			m_needsRenderUpdate = true;
		}
		
		return isAllowed;
	}
	
	@Override
	public boolean isBlockSolidOnSide( int x, int y, int z, ForgeDirection side, boolean defaultValue )
	{
		m_lookupCoords.set( x, y, z );
		Block block = Block.blocksList[getBlockId( m_lookupCoords )];
		if( block == null )
		{
			return defaultValue;
		}
		return block.isBlockSolidOnSide( this, x, y, z, side );
	}
	
	@Override
	public int getLightBrightnessForSkyBlocks( int x, int y, int z, int blockBrightness )
	{
		if( m_ship == null )
		{
			return 0;
		}
		
		// convert the block position into a world block
		Vec3 v = Vec3.createVectorHelper( x, y, z );
		m_ship.blocksToShip( v );
		m_ship.shipToWorld( v );
		x = MathHelper.floor_double( v.xCoord );
		y = MathHelper.floor_double( v.yCoord );
		z = MathHelper.floor_double( v.zCoord );
		return m_ship.worldObj.getLightBrightnessForSkyBlocks( x, y, z, blockBrightness );
	}
	
	@Override
	@SuppressWarnings( "rawtypes" )
	public List getEntitiesWithinAABB( Class theClass, AxisAlignedBB box )
	{
		// there are no entities in ship world
		return new ArrayList();
		
		// UNDONE: actually do this query?
		// get the AABB for the query box in world coords
		// get the entities from the real world
		// transform them into ship world
	}
	
	@Override
	public void markTileEntityChunkModified( int x, int y, int z, TileEntity tileEntity )
	{
		// don't need to do anything
	}
	
	@Override
	public void updateEntities( )
	{
		// update the tile entities
		Iterator<Map.Entry<ChunkCoordinates,TileEntity>> iter = m_tileEntities.entrySet().iterator();
		while( iter.hasNext() )
		{
			Map.Entry<ChunkCoordinates,TileEntity> entry = iter.next();
			ChunkCoordinates coords = entry.getKey();
			TileEntity entity = entry.getValue();
			
			try
			{
				entity.updateEntity();
			}
			catch( Exception ex )
			{
				// remove the offending tile entity
				iter.remove();
				
				Ships.logger.warning(
					ex,
					"Tile entity %s at (%d,%d,%d) had a problem during an update! The tile entity has been removed from its block to prevent further errors.",
					entity.getClass().getName(),
					coords.posX, coords.posY, coords.posZ
				);
			}
		}
		
		// on the client, do random update ticks
		if( Environment.isClient() && m_ship != null )
		{
			updateEntitiesClient();
		}
		
		// on the server, push any accumulated changes to the client
		if( Environment.isServer() && !m_changedBlocks.isEmpty() )
		{
			pushBlockChangesToClients();
			m_changedBlocks.clear();
		}
	}
	
	@SideOnly( Side.CLIENT )
	private void updateEntitiesClient( )
	{
		// get the player position on the ship
		EntityPlayer player = Minecraft.getMinecraft().thePlayer;
		Vec3 v = Vec3.createVectorHelper( player.posX, player.posY, player.posZ );
		m_ship.worldToShip( v );
		m_ship.shipToBlocks( v );
		int playerX = MathHelper.floor_double( v.xCoord );
		int playerY = MathHelper.floor_double( v.yCoord );
		int playerZ = MathHelper.floor_double( v.zCoord );
		
		Random random = new Random();
		for( int i=0; i<1000; i++ )
		{
			int x = playerX + random.nextInt( 16 ) - random.nextInt( 16 );
			int y = playerY + random.nextInt( 16 ) - random.nextInt( 16 );
			int z = playerZ + random.nextInt( 16 ) - random.nextInt( 16 );
			int blockId = getBlockId( x, y, z );
			if( blockId > 0 )
			{
				Block.blocksList[blockId].randomDisplayTick( this, x, y, z, random );
			}
		}
	}

	private void pushBlockChangesToClients( )
	{
		if( m_ship == null )
		{
			return;
		}
		
		MinecraftServer.getServer().getConfigurationManager().sendToAllNear(
			m_ship.posX, m_ship.posY, m_ship.posZ, 64,
			m_ship.worldObj.provider.dimensionId,
			new PacketChangedBlocks( m_ship, m_changedBlocks ).getCustomPacket()
		);
	}
	
	@Override
	public void addBlockEvent( int x, int y, int z, int blockId, int eventId, int eventParam )
	{
		if( m_ship == null || blockId == 0 || getBlockId( x, y, z ) != blockId )
		{
			return;
		}
		
		// on the client, just deliver to the block
		boolean eventWasAccepted = Block.blocksList[blockId].onBlockEventReceived( this, x, y, z, eventId, eventParam );
		
		// on the server, also send a packet to the client
		if( Environment.isServer() && eventWasAccepted )
		{
			// get the pos in world space
			Vec3 v = Vec3.createVectorHelper( x, y, z );
			m_ship.blocksToShip( v );
			m_ship.shipToWorld( v );
			
			MinecraftServer.getServer().getConfigurationManager().sendToAllNear(
				v.xCoord, v.yCoord, v.zCoord, 64,
				m_ship.worldObj.provider.dimensionId,
				new PacketShipBlockEvent( m_ship.entityId, x, y, z, blockId, eventId, eventParam ).getCustomPacket()
			);
		}
	}
	
	@Override
	public void playSoundEffect( double x, double y, double z, String sound, float volume, float pitch )
    {
		if( sound == null )
		{
			return;
		}
		
		// on the server, send a packet to the clients
		if( Environment.isServer() )
		{
			// get the pos in world space
			Vec3 v = Vec3.createVectorHelper( x, y, z );
			m_ship.blocksToShip( v );
			m_ship.shipToWorld( v );
			
			MinecraftServer.getServer().getConfigurationManager().sendToAllNear(
				v.xCoord, v.yCoord, v.zCoord, volume > 1.0F ? (double)(16.0F * volume) : 16.0D,
				m_ship.worldObj.provider.dimensionId,
				new Packet62LevelSound( sound, v.xCoord, v.yCoord, v.zCoord, volume, pitch )
			);
		}
		
		// on the client, just ignore. Sounds actually get played by the packet handler
    }
	
	@Override
	public void spawnParticle( String name, double x, double y, double z, double motionX, double motionY, double motionZ )
	{
		if( m_ship == null )
		{
			return;
		}
		
		// transform the position to world coordinates
		Vec3 v = Vec3.createVectorHelper( x, y, z );
		m_ship.blocksToShip( v );
		m_ship.shipToWorld( v );
		x = v.xCoord;
		y = v.yCoord;
		z = v.zCoord;
		
		// transform the velocity vector too
		v.xCoord = motionX;
		v.yCoord = motionY;
		v.zCoord = motionZ;
		m_ship.shipToWorldDirection( v );
		motionX = v.xCoord;
		motionY = v.yCoord;
		motionZ = v.zCoord;
		
		m_ship.worldObj.spawnParticle( name, x, y, z, motionX, motionY, motionZ );
	}
	
	@Override
	public boolean spawnEntityInWorld( Entity entity )
    {
		if( m_ship == null )
		{
			return false;
		}
		
		// transform the entity position to world coordinates
		Vec3 v = Vec3.createVectorHelper( entity.posX, entity.posY, entity.posZ );
		m_ship.blocksToShip( v );
		m_ship.shipToWorld( v );
		entity.posX = v.xCoord;
		entity.posY = v.yCoord;
		entity.posZ = v.zCoord;
		
		// transform the velocity vector too
		v.xCoord = entity.motionX;
		v.yCoord = entity.motionY;
		v.zCoord = entity.motionZ;
		m_ship.shipToWorldDirection( v );
		entity.motionX = v.xCoord;
		entity.motionY = v.yCoord;
		entity.motionZ = v.zCoord;
		
		// pass off to the outer world
		entity.worldObj = m_ship.worldObj;
		return m_ship.worldObj.spawnEntityInWorld( entity );
    }
}
