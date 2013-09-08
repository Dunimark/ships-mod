package cuchaz.ships;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public class BlockHelm extends Block
{
	protected BlockHelm( int blockId )
	{
		super( blockId, Material.wood );
		
		setHardness( 2.0F );
		setResistance( 5.0F );
		setStepSound( soundWoodFootstep );
		setUnlocalizedName( "helm" );
		setCreativeTab( CreativeTabs.tabTransport );
	}
	
	@Override
	public boolean isOpaqueCube( )
	{
		return false;
	}
	
	@Override
	public boolean renderAsNormalBlock( )
	{
		return false;
	}
	
	@Override
	public int getRenderType( )
	{
		return -1;
	}
	
	@Override
	public boolean hasTileEntity( int metadata )
	{
		return true;
	}
	
	@Override
	public TileEntity createTileEntity( World world, int metadata )
	{
		return new TileEntityHelm();
	}
}