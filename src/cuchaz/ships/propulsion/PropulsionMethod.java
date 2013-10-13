package cuchaz.ships.propulsion;

import java.util.Set;
import java.util.TreeSet;

import net.minecraft.util.ChunkCoordinates;

public abstract class PropulsionMethod
{
	private String m_name;
	private String m_namePlural;
	private Set<ChunkCoordinates> m_coords;
	
	protected PropulsionMethod( String name, String namePlural )
	{
		this( name, namePlural, new TreeSet<ChunkCoordinates>() );
	}
	
	protected PropulsionMethod( String name, String namePlural, Set<ChunkCoordinates> coords )
	{
		m_name = name;
		m_namePlural = namePlural;
		m_coords = coords;
	}
	
	public String getName( )
	{
		return m_name;
	}
	
	public String getNamePlural( )
	{
		return m_namePlural;
	}
	
	public Set<ChunkCoordinates> getCoords( )
	{
		return m_coords;
	}
	
	public abstract double getThrust( );
}