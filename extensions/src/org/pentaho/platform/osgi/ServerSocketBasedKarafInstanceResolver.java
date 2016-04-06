/*
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright 2016 Pentaho Corporation. All rights reserved.
 */
package org.pentaho.platform.osgi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileLock;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This implementation resolves Karaf instance numbers based on a ServerSocket port strategy.
 * <p/>
 * It also handles assigning a cache folder appropriate for the client type (spoon, kitchen, carte, etc) guaranteed to
 * not be in use by another instance.
 * <p/>
 * Created by nbaker on 3/21/16.
 */
class ServerSocketBasedKarafInstanceResolver implements IKarafInstanceResolver {
  private static final int START_PORT_NUMBER = 10000;
  private static final int MAX_NUMBER_OF_KARAF_INSTANCES = 1000;
  public static final String DATA = "data";
  private Logger logger = LoggerFactory.getLogger( getClass() );

  @Override public void resolveInstance( KarafInstance instance ) throws KarafInstanceResolverException {

    // Obtaining a valid instance number in and of itself isn't sufficient. Since ports will be assigned based on
    // the instance number as an offset all ports must resolve as well otherwise the instance isn't valid and another
    // should be tried.
    int latestOffsetTried = 0;
    do {
      latestOffsetTried = resolveInstanceNumber( instance, latestOffsetTried );
    } while ( !resolvePorts( instance ) );

    // If no exception was thrown we're here now with all ports resolved
    assignAvailableCacheFolderForType( instance );

  }

  private void assignAvailableCacheFolderForType( KarafInstance instance ) {
    String cacheParentFolder = instance.getCacheParentFolder();
    File clientTypeCacheFolder = new File( cacheParentFolder + "/" + instance.getClientType() );
    clientTypeCacheFolder.mkdirs();
    File[] dataDirectories = clientTypeCacheFolder.listFiles( new FilenameFilter() {
      @Override public boolean accept( File dir, String name ) {
        return name.startsWith( DATA );
      }
    } );
    int maxInstanceNoFound = 0;
    Pattern pattern = Pattern.compile( DATA + "\\-([0-9]+)" );

    for ( File dataDirectory : dataDirectories ) {
      boolean locked = true;
      Matcher matcher = pattern.matcher( dataDirectory.getName() );
      if ( !matcher.matches() ) {
        continue;
      }
      int instanceNo = Integer.parseInt( matcher.group( 1 ) );

      maxInstanceNoFound = Math.max( maxInstanceNoFound, instanceNo );

      File lockFile = new File( dataDirectory, ".lock" );
      FileLock lock = null;
      if ( !lockFile.exists() ) {
        locked = false;
      } else {
        try {
          FileOutputStream fileOutputStream = new FileOutputStream( lockFile );
          try {
            lock = fileOutputStream.getChannel().tryLock();
            if ( lock != null ) { // not locked by another program
              instance.setCacheLock( lock );
              locked = false;
            }
          } catch ( Exception e ) {
            // Lock active on another program
          }
        } catch ( FileNotFoundException e ) {
          logger.error( "Error locking file in data cache directory", e );
        }
      }

      if ( !locked ) {
        instance.setCachePath( dataDirectory.getPath() );
        break;
      }
    }
    if ( instance.getCachePath() == null ) {
      File newCacheFolder = null;
      while ( newCacheFolder == null ) {
        maxInstanceNoFound++;
        File candidate = new File( clientTypeCacheFolder, DATA + "-" + maxInstanceNoFound );
        if ( candidate.exists() ) {
          // Another process slipped in and created a folder, lets skip over them
          continue;
        }
        newCacheFolder = candidate;
      }
      FileOutputStream fileOutputStream = null;
      try {
        newCacheFolder.mkdir();
        File lockFile = new File( newCacheFolder, ".lock" );
        fileOutputStream = new FileOutputStream( lockFile );
        FileLock lock = fileOutputStream.getChannel().lock();
        instance.setCachePath( newCacheFolder.getPath() );
        instance.setCacheLock( lock );
      } catch ( IOException e ) {
        logger.error( "Error creating data cache folder", e );
      }
    }


  }

  private boolean resolvePorts( KarafInstance instance ) {
    List<KarafInstancePort> ports = instance.getPorts();
    int instanceNumber = instance.getInstanceNumber();
    for ( KarafInstancePort port : ports ) {
      int portNo = port.getStartPort() + instanceNumber;
      if ( !isPortAvailable( portNo ) ) {
        return false;
      }
      port.setAssignedPort( portNo );
    }

    return true;
  }

  private boolean isPortAvailable( int port ) {

    try ( Socket socket = new Socket( "localhost", port ) ) {
      return false;
    } catch ( IOException e ) {
      return true;
    }
  }

  private int resolveInstanceNumber( KarafInstance instance, int latestOffsetTried )
      throws KarafInstanceResolverException {
    logger.debug( "Attempting to resolve available Karaf instance number by way of Server Socket" );
    int testInstance = latestOffsetTried + 1;
    Integer instanceNo = null;
    do {

      int candidate = START_PORT_NUMBER + testInstance;
      Socket socket = null;
      try {
        socket = new Socket( "localhost", candidate );
        socket.close();
      } catch ( ConnectException e ) {
        // port not in-use
        instanceNo = testInstance;
        try {
          ServerSocket ssocket = new ServerSocket( candidate );
          instance.setInstanceSocket( ssocket );
        } catch ( IOException e1 ) {
          logger.error( "Error creating ServerSocket", e1 );
        }

        logger.debug( "Karaf instance resolved to: " + instanceNo );
        instance.setInstanceNumber( instanceNo );

      } catch ( IOException ignored ) {
        // Some other error, move to next candidate
      }
    } while ( instanceNo == null && testInstance++ <= MAX_NUMBER_OF_KARAF_INSTANCES );

    if ( instanceNo == null ) {
      throw new KarafInstanceResolverException( "Unable to resolve Karaf Instance number" );
    }
    return instanceNo;
  }
}