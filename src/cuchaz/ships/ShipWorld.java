package cuchaz.ships;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

import org.apache.commons.codec.binary.Base64;

public class ShipWorld extends DetatchedWorld
{
	private static class BlockStorage
	{
		public int blockId;
		public int blockMeta;
		
		// UNDONE: save other block data?
		
		public BlockStorage( )
		{
			blockId = 0;
			blockMeta = 0;
		}

		public void writeData( DataOutputStream out )
		throws IOException
		{
			out.writeInt( blockId );
			out.writeInt( blockMeta );
		}

		public void readData( DataInputStream in )
		throws IOException
		{
			blockId = in.readInt();
			blockMeta = in.readInt();
		}
		
		public void copyFromWorld( World world, ChunkCoordinates coords )
		{
			blockId = world.getBlockId( coords.posX, coords.posY, coords.posZ );
			blockMeta = world.getBlockMetadata( coords.posX, coords.posY, coords.posZ );
		}
		
		public void copyToWorld( World world, ChunkCoordinates coords )
		{
			world.setBlock( coords.posX, coords.posY, coords.posZ, blockId );
			world.setBlockMetadataWithNotify( coords.posX, coords.posY, coords.posZ, blockMeta, 3 );
		}
	}
	
	// NOTE: this static var is ok since the logic loop is single-threaded
	private static ChunkCoordinates m_lookupCoords = new ChunkCoordinates( 0, 0, 0 );
	
	private EntityShip m_ship;
	private TreeMap<ChunkCoordinates,BlockStorage> m_blocks;
	private final BlockStorage m_airBlockStorage;
	
	private ShipWorld( World world )
	{
		super( world, "Ship" );
	    
		// init defaults
		m_ship = null;
		m_blocks = null;
		m_airBlockStorage = new BlockStorage();
	}
	
	public ShipWorld( World world, ChunkCoordinates shipBlock, List<ChunkCoordinates> blocks )
	{
		this( world );
		
		m_blocks = new TreeMap<ChunkCoordinates,BlockStorage>();
		
		// save the ship block
		BlockStorage storage = new BlockStorage();
		storage.copyFromWorld( world, shipBlock );
		m_blocks.put( new ChunkCoordinates( 0, 0, 0 ), storage );
		
		// save the rest of the blocks
		for( ChunkCoordinates worldCoords : blocks )
		{
			storage = new BlockStorage();
			storage.copyFromWorld( world, worldCoords );
			
			// make all the blocks relative to the ship block
			ChunkCoordinates relativeCoords = new ChunkCoordinates(
				worldCoords.posX - shipBlock.posX,
				worldCoords.posY - shipBlock.posY,
				worldCoords.posZ - shipBlock.posZ
			);
			m_blocks.put( relativeCoords, storage );
		}
	}
	
	public ShipWorld( World world, byte[] data )
	{
		this( world );
		
		DataInputStream in = new DataInputStream( new ByteArrayInputStream( data ) );
		try
		{
			// read the version number
			int version = in.readInt();
			if( version != 0 )
			{
				System.err.println( "ShipBlocks persistence version " + version + " not supported! Blocks loading skipped!" );
			}
			else
			{
				// read the blocks
				m_blocks = new TreeMap<ChunkCoordinates,BlockStorage>();
				int numBlocks = in.readInt();
				for( int i=0; i<numBlocks; i++ )
				{
					ChunkCoordinates coords = new ChunkCoordinates(
						in.readInt(),
						in.readInt(),
						in.readInt()
					);
					
					BlockStorage storage = new BlockStorage();
					storage.readData( in );
					
					m_blocks.put( coords, storage );
				}
			}
		}
		catch( IOException ex )
		{
			throw new Error( "Unable to deserialize blocks!", ex );
		}
	}
	
	public ShipWorld( World world, String data )
	{
		this( world, Base64.decodeBase64( data ) );
	}
	
	public void restoreToWorld( World world, int x, int y, int z )
	{
		for( Map.Entry<ChunkCoordinates,BlockStorage> entry : m_blocks.entrySet() )
		{
			ChunkCoordinates coords = entry.getKey();
			BlockStorage storage = entry.getValue();
			storage.copyToWorld( world, new ChunkCoordinates( coords.posX + x, coords.posY + y, coords.posZ + z ) );
		}
	}
	
	public EntityShip getShip( )
	{
		return m_ship;
	}
	public void setShip( EntityShip val )
	{
		m_ship = val;
	}
	
	public int getNumBlocks( )
	{
		return m_blocks.size();
	}
	
	public Iterable<ChunkCoordinates> coords( )
	{
		return m_blocks.keySet();
	}
	
	public BlockStorage getStorage( ChunkCoordinates coords )
	{
		BlockStorage storage = m_blocks.get( coords );
		if( storage == null )
		{
			storage = m_airBlockStorage;
		}
		return storage;
	}
	
	@Override
	public int getBlockId( int x, int y, int z )
	{
		m_lookupCoords.set( x, y, z );
		return getBlockId( m_lookupCoords );
	}
	
	public int getBlockId( ChunkCoordinates coords )
	{
		return getStorage( coords ).blockId;
	}
	
	@Override
	public TileEntity getBlockTileEntity( int x, int y, int z )
	{
		// UNDONE: support tile entities?
		return null;
	}
	
	@Override
	public int getBlockMetadata( int x, int y, int z )
	{
		m_lookupCoords.set( x, y, z );
		return getBlockMetadata( m_lookupCoords );
	}
	
	public int getBlockMetadata( ChunkCoordinates coords )
	{
		return getStorage( coords ).blockMeta;
	}
	
	@Override
	public boolean setBlock( int par1, int par2, int par3, int par4, int par5, int par6 )
	{
		// do nothing. Blocks are immutable
		return false;
	}
	
	public ChunkCoordinates getMin( )
	{
		ChunkCoordinates min = new ChunkCoordinates( 0, 0, 0 );
		for( ChunkCoordinates coords : m_blocks.keySet() )
		{
			min.posX = Math.min( min.posX, coords.posX );
			min.posY = Math.min( min.posY, coords.posY );
			min.posZ = Math.min( min.posZ, coords.posZ );
		}
		return min;
	}
	
	public ChunkCoordinates getMax( )
	{
		ChunkCoordinates max = new ChunkCoordinates( 0, 0, 0 );
		for( ChunkCoordinates coords : m_blocks.keySet() )
		{
			max.posX = Math.max( max.posX, coords.posX );
			max.posY = Math.max( max.posY, coords.posY );
			max.posZ = Math.max( max.posZ, coords.posZ );
		}
		return max;
	}
	
	public byte[] getData( )
	{
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream( data );
		
		// UNDONE: we could use compression here if we need it
		
		try
		{
			// write out persistence version number
			out.writeInt( 0 );
			
			// write out the blocks
			out.writeInt( m_blocks.size() );
			for( Map.Entry<ChunkCoordinates,BlockStorage> entry : m_blocks.entrySet() )
			{
				ChunkCoordinates coords = entry.getKey();
				BlockStorage storage = entry.getValue();
				
				out.writeInt( coords.posX );
				out.writeInt( coords.posY );
				out.writeInt( coords.posZ );
				storage.writeData( out );
			}
		}
		catch( IOException ex )
		{
			throw new Error( "Unable to serialize blocks!", ex );
		}
		
		return data.toByteArray();
	}
	
	public String getDataString( )
	{
		return Base64.encodeBase64String( getData() );
	}
}