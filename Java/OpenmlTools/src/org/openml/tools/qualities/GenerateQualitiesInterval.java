package org.openml.tools.qualities;

import org.openml.apiconnector.algorithms.Conversion;
import org.openml.apiconnector.algorithms.QueryUtils;
import org.openml.apiconnector.io.OpenmlConnector;
import org.openml.apiconnector.settings.Config;
import org.openml.apiconnector.xml.DataSetDescription;
import org.openml.webapplication.features.FantailConnector;

public class GenerateQualitiesInterval {

	// program for auto filling of data qualities of interval 1000
	public static void main( String[] args ) throws Exception {
		
		Config config = new Config("username = janvanrijn@gmail.com; password = Feyenoord2002; server = http://localhost/openexpdb_v2/");
		
		OpenmlConnector api = new OpenmlConnector( config.getServer() );
		
		int interval_size = 1000;
		String sql = "SELECT `did` FROM `dataset` WHERE `did` NOT IN (SELECT DISTINCT `data` FROM `data_quality_interval` WHERE `interval_end` - `interval_start` = "+interval_size+")";
		
		int[] dataset_ids = QueryUtils.getIdsFromDatabase(api, sql);
		
		if( dataset_ids.length == 0 ) {
			Conversion.log("OK", "Extract Qualities", "No datasets to process. ");
		}
		
		for( int did : dataset_ids ) {
			try {
				DataSetDescription dsd = api.openmlDataDescription(did);
				FantailConnector.extractFeatures(did, dsd.getDefault_target_attribute(), interval_size, config);
				
			} catch( Exception e ) { System.err.println("Error at " + did + ": " + e.getMessage() ); }
		}
		
	}
}
