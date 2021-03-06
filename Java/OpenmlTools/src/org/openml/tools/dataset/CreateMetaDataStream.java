package org.openml.tools.dataset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openml.apiconnector.algorithms.Conversion;
import org.openml.apiconnector.algorithms.QueryUtils;
import org.openml.apiconnector.io.OpenmlConnector;
import org.openml.apiconnector.settings.Config;
import org.openml.apiconnector.xml.DataQuality;
import org.openml.apiconnector.xml.DataQuality.Quality;
import org.openml.apiconnector.xml.TaskEvaluations;
import org.openml.apiconnector.xml.TaskEvaluations.Evaluation;
import org.openml.tools.algorithms.InstancesHelper;

import weka.core.Attribute;
import weka.core.Instances;
import weka.filters.unsupervised.attribute.RemoveUnusedClassValues;
import weka.filters.unsupervised.attribute.RemoveUseless;

public class CreateMetaDataStream {
	private static Config config = new Config();
	private static OpenmlConnector apiconnector;
	private static Boolean TEST_MODE = false;
	private static int INTERVALS_PER_DOWNLOAD = 100;

	private Map<String, Integer> allQualities = new HashMap<String, Integer>();
	private Map<String, Integer> allClassifiers = new HashMap<String, Integer>();

	public static void main(String[] args) throws Exception {
		Integer[] task_ids = { 2056, 171, 170, 175, 174, 163, 160, 185, 190,
				191, 188, 189, 178, 177, 182, 183, 2133, 2132, 2134, 2129, 200,
				2128, 2131, 2130, 197, 196, 199, 198, 193, 192, 195, 194, 2126,
				2127, 2167, 2166, 2165, 2164, 2163, 2162, 2160, 2150, 2151,
				127, 2159, 2156, 2157, 2154, 122 };

		if (config.getServer() != null) {
			apiconnector = new OpenmlConnector(config.getServer());
		} else {
			apiconnector = new OpenmlConnector();
		}

		new CreateMetaDataStream(task_ids, "meta_stream", 1000);
	}

	public CreateMetaDataStream(Integer[] task_ids, String name,
			int interval_size) throws Exception {
		// download all task evaluations...
		Map<String, MetaDataStreamInstance> instances = getAllStreamInstances(
				task_ids, interval_size);

		// now add the data qualities: (slow process)
		int counter = 0;
		List<String> toDelete = new ArrayList<String>();
		for (String key : instances.keySet()) {
			Conversion.log("OK", "Create MetaDatastream",
					"Downloading data qualities for key: " + key + "("
							+ (++counter) + "/" + instances.size() + ")");
			MetaDataStreamInstance instance = instances.get(key);
			try {
				DataQuality dq = apiconnector.openmlDataQuality(
						instance.getDid(), instance.getInterval_start()
								- interval_size, instance.getInterval_end()
								- interval_size, interval_size);
				for (Quality quality : dq.getQualities()) {
					if (allQualities.containsKey(quality.getName()) == true) {
						allQualities.put(quality.getName(),
								allQualities.get(quality.getName()) + 1);
					} else {
						allQualities.put(quality.getName(), 1);
					}

					instance.addDataQuality(quality.getName(),
							Double.parseDouble(quality.getValue()));
				}
			} catch (Exception e) { // corrupt data
				toDelete.add(key);
			}
		}

		for (String key : toDelete) {
			Conversion.log("WARNING", "Generate Meta DataStream",
					"Removing corrupt instance: " + instances.get(key));
			instances.remove(key);
		}
		Conversion.log("WARNING", "Generate Meta DataStream",
				"Removed a number of corrupt instances: " + toDelete.size());

		Instances dataset = createInstanceHeader(name, instances.size());

		for (String key : instances.keySet()) {
			MetaDataStreamInstance instance = instances.get(key);
			dataset.add(instance.toInstance(dataset));
		}

		// remove attributes that are unused
		Conversion.log("OK", "Create MetaDatastream",
				"Start applying filters... ");
		dataset = InstancesHelper.applyFilter(dataset, new RemoveUseless(),
				"-M 100.0 ");
		dataset = InstancesHelper.applyFilter(dataset,
				new RemoveUnusedClassValues(), "-T 0");
		dataset.setRelationName(name);

		Conversion.log("OK", "Create MetaDatastream", "Start writing to file: "
				+ name);
		InstancesHelper.toFile(dataset, name);
		Conversion.log("OK", "Create MetaDatastream", "Done.");
	}

	private Map<String, MetaDataStreamInstance> getAllStreamInstances(
			Integer[] task_ids, int interval_size) throws Exception {
		Map<String, MetaDataStreamInstance> instances = new HashMap<String, MetaDataStreamInstance>(); // indexing
																										// with
																										// String
																										// is
																										// soooo
																										// wrong.
		for (Integer task_id : task_ids) {
			Conversion.log("OK", "Create MetaDatastream", "Downloading Task: "
					+ task_id);

			String sql = "SELECT `q`.`value` FROM `data_quality` `q`, `task_values` `t` WHERE `t`.`input` = 1 AND `q`.`quality` = 'NumberOfInstances' AND `t`.`value` = `q`.`data` AND `t`.`task_id` = "
					+ task_id;
			double task_size = QueryUtils.getIntFromDatabase(apiconnector, sql);

			for (int i = interval_size; i < task_size; i += INTERVALS_PER_DOWNLOAD
					* interval_size) {
				Conversion.log("OK", "Create MetaDatastream",
						"Downloading Task Evaluations: " + task_id
								+ " interval " + i + " - "
								+ (i + INTERVALS_PER_DOWNLOAD * interval_size)
								+ " OF " + task_size);

				TaskEvaluations te = apiconnector.openmlTaskEvaluations(
						task_id, i, i + INTERVALS_PER_DOWNLOAD * interval_size,
						interval_size);
				if (te.getEvaluation() != null) {
					for (Evaluation evaluation : te.getEvaluation()) {

						try {
							String key = task_id + "_"
									+ evaluation.getInterval_start();
							Double predictive_accuracy = Double
									.parseDouble(evaluation
											.getMeasure("predictive_accuracy"));
							if (instances.containsKey(key) == false) {
								instances.put(
										key,
										new MetaDataStreamInstance(task_id, te
												.getInput_data(), evaluation
												.getInterval_start(),
												evaluation.getInterval_end()));
							}
							if (allClassifiers.containsKey(evaluation
									.getImplementation()) == true) {
								allClassifiers.put(evaluation
										.getImplementation(),
										allClassifiers.get(evaluation
												.getImplementation()) + 1);
							} else {
								allClassifiers.put(
										evaluation.getImplementation(), 1);
							}
							instances.get(key).addClassifierScore(
									evaluation.getImplementation(),
									predictive_accuracy);
						} catch (Exception e) {
							Conversion.log("WARNING",
									"Generate Meta DataStream", e.getMessage());
						}
					}
				}
			}
		}
		return instances;
	}

	private Instances createInstanceHeader(String relationName, int numInstances) {
		ArrayList<Attribute> attributes = new ArrayList<Attribute>();

		attributes.add(new Attribute(MetaDataStreamInstance.ATT_TASK_ID_NAME));
		attributes.add(new Attribute(
				MetaDataStreamInstance.ATT_INTERVAL_START_NAME));
		attributes.add(new Attribute(
				MetaDataStreamInstance.ATT_INTERVAL_END_NAME));

		ArrayList<String> classValues = new ArrayList<String>();
		for (String classifier : allClassifiers.keySet()) {
			if (allClassifiers.get(classifier) < (numInstances * 0.8)) {
				Conversion.log("WARNING", "Generate Meta DataStream",
						"Dropping classifier since to few runs: " + classifier);
				continue;
			}
			attributes.add(new Attribute(
					MetaDataStreamInstance.ATT_CLASSIFIER_PREFIX + classifier));
			classValues.add(classifier);
		}

		for (String quality : allQualities.keySet()) {
			if (allQualities.get(quality) < (numInstances * 0.75)) {
				Conversion.log("WARNING", "Generate Meta DataStream",
						"Dropping quality since to few runs: " + quality);
				continue;
			}
			attributes.add(new Attribute(MetaDataStreamInstance.ATT_META_PREFIX
					+ quality));
		}

		Attribute classAtt = new Attribute(
				MetaDataStreamInstance.ATT_CLASS_NAME, classValues);
		attributes.add(classAtt);
		Instances instances = new Instances(relationName, attributes,
				numInstances);
		instances.setClass(classAtt);

		return instances;
	}
}
