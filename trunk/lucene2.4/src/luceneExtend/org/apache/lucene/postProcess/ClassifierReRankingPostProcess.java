package org.apache.lucene.postProcess;


import gnu.trove.TIntHash;
import gnu.trove.TIntHashSet;
import gnu.trove.TIntIntHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.search.RBooleanQuery;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.TopDocCollector;
import org.dutir.lucene.evaluation.TRECQrelsInMemory;
import org.dutir.lucene.util.ApplicationSetup;
import org.dutir.lucene.util.ExpansionTerms;

import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.functions.LibSVM;
import weka.classifiers.functions.SVMreg;
import weka.classifiers.functions.Winnow;
import weka.classifiers.functions.supportVector.Kernel;
import weka.classifiers.functions.supportVector.NormalizedPolyKernel;
import weka.core.Attribute;
import weka.core.FastVector;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SelectedTag;

/**
 * This class is used for reranking the search results based on regression
 * algorithms (Note it's regression, not classification)
 * parameters: 1. ClassifierReRanking.numOfTermFeatures
 * 
 * @author YeZheng
 * 
 */
public class ClassifierReRankingPostProcess extends QueryExpansion {
	protected static Logger logger = Logger.getRootLogger();
	static TRECQrelsInMemory trecR = new TRECQrelsInMemory();

	/**
	 * Caching the query expansion models that have been created so far.
	 */
	protected Map<String, QueryExpansionModel> Cache_RerankingModel = new HashMap<String, QueryExpansionModel>();
//	/** The document index used for retrieval. */
//	protected DocumentIndex documentIndex;
//	/** The inverted index used for retrieval. */
//	protected InvertedIndex invertedIndex;
//	/** An instance of Lexicon class. */
//	protected Lexicon lexicon;
//	/** The direct index used for retrieval. */
//	protected DirectIndex directIndex;
//	/** The statistics of the index */
//	protected CollectionStatistics collStats;
//	/** The query expansion model used. */
//	protected Request request;
	
	protected int FutureSize = 0;
	protected FastVector fvNominalVal = null;
	protected Instances trainingSet = null;
	protected WeightingModel wmodel = null;

	TermFreqVector t_tfs_cache[] = null;

	// int reRankreRankingNumingNum = Integer.parseInt(ApplicationSetup
	// .getProperty("ClassifierReRanking.reRankingNum", "50"));
	int positiveNum = Integer.parseInt(ApplicationSetup.getProperty(
			"ClassifierReRanking.positiveNum", "10"));
	int negetiveNum = Integer.parseInt(ApplicationSetup.getProperty(
			"ClassifierReRanking.negetiveNum", "10"));
	int reRankNum = Integer.parseInt(ApplicationSetup.getProperty(
			"ClassifierReRanking.reRankNum", "50"));
	private String classifierName = ApplicationSetup.getProperty(
			"ClassifierReRanking.classifierName", "NaiveBayes");
	private boolean CLTag = Boolean.parseBoolean(ApplicationSetup.getProperty(
			"ClassifierReRanking.CLTag", "false"));
	private double k = Double.parseDouble(ApplicationSetup.getProperty(
			"ClassifierReRanking.k", "0.5"));
	int maxNFeatures = Integer.parseInt(ApplicationSetup.getProperty(
			"ClassifierReRanking.numOfTermFeatures", "100"));
	boolean includeQueryTermTag = Boolean.parseBoolean(ApplicationSetup
			.getProperty("ClassifierReRanking.includeQueryTermTag", "false"));
	private boolean Mitra_tag = Boolean.parseBoolean(ApplicationSetup
			.getProperty("ClassifierReRanking.Mitra_tag", "true"));
	private float[] mitraScores;
	private Attribute docLenAtt;
	private Attribute mitraAtt;
	QERerankPostProcess qerPP;
	private ExpansionTerms expansionTerm;
	private float[] scores;

	/**
	 * (non-Javadoc)
	 * expansionTerm
	 * @see uk.ac.gla.terrier.querying.PostProcess#getInfo()
	 */
	public String getInfo() {
		return "Classifier_" + classifierName + "_pNum=" + positiveNum
				+ "_nNum=" + negetiveNum + "_rNum=" + reRankNum
				+ "_maxNFeatures=" + maxNFeatures + "_k=" + k;
	}

	public TopDocCollector postProcess(RBooleanQuery query,
			TopDocCollector topDoc, Searcher seacher) {
		setup(query, topDoc, seacher); // it is necessary
		MitraReRankingPostProcess mitraPP = new MitraReRankingPostProcess();
		mitraPP.pre_process();
		mitraScores = mitraPP.mitra_socres;
		t_tfs_cache = mitraPP.t_tfs_cache;

//		CollectionStatistics collectionStatistics = index
//				.getCollectionStatistics();
		// get the query expansion model to use
		String qeModel = ApplicationSetup.getProperty(
				"Lucene.QueryExpansion.Model", "KL");
//		if (qeModel == null || qeModel.length() == 0) {
//			logger.warn("qemodel control not set for QueryExpansion"
//					+ " post process. Using default model Bo1");
//			qeModel = "Bo1";
//		}
		
		
		
//		setQueryExpansionModel(getQueryExpansionModel(qeModel));
//		if (directIndex == null) {
//			logger
//					.error("This index does not have a direct index. Query expansion disabled!!");
//			return;
//		}
//		
//		wmodel = (WeightingModel) manager.getWeightingModel(((Request) q)
//				.getWeightingModel());
//		wmodel.setNumberOfTokens((double) collectionStatistics
//				.getNumberOfTokens());
//		wmodel.setNumberOfDocuments((double) collectionStatistics
//				.getNumberOfDocuments());
//		wmodel.setAverageDocumentLength((double) collectionStatistics
//				.getAverageDocumentLength());
//		wmodel.setNumberOfUniqueTerms((double) collectionStatistics
//				.getNumberOfUniqueTerms());
//		wmodel.setNumberOfPointers((double) collectionStatistics
//				.getNumberOfPointers());

		// in order to save the time from references to the arrays, we create
		// local references
//		ResultSet resultSet = q.getResultSet();
		int set_size = this.ScoreDoc.length;
		int docids[] = new int[set_size];
		scores = new float[set_size];
		for (int i = 0; i < set_size; i++) {
			docids[i] = this.ScoreDoc[i].doc;
			scores[i] = this.ScoreDoc[i].score;
		}

		// ****************instant and train the
		// classifier************************

		if (set_size < positiveNum + negetiveNum) {
			logger.info("the num of retrieved result is too small");
			return topDoc;
		}

		qerPP = new QERerankPostProcess();
		qerPP.setup(manager, q);
		qerPP.setQueryExpansionModel(QEModel);
		expansionTerm = qerPP.expandQuery(this.request.getMatchingQueryTerms(),
				resultSet, positiveNum);

		Classifier cModel = getClassifier(classifierName);
		train(cModel, docids, scores, set_size); 

		// ***************Reranking process*******************************
		int rNum = Math.min(this.reRankNum, set_size);
		HeapSort.descendingFirstKHeapSort(scores, docids, occurences, rNum);
		// HeapSort.descendingHeapSort(scores, docids, occurences, set_size);
	}

	/**
	 * todo
	 * 
	 * @param classfierName
	 * @return
	 */
	private Classifier getClassifier(String classfierName) {
		if (classfierName.equals("NaiveBayes")) {
			return (Classifier) new NaiveBayes();
		} else if (classfierName.equals("SVM")
				|| classfierName.equals("weka.classifiers.functions.LibSVM")) {
			LibSVM svm = new LibSVM();
			svm.setProbabilityEstimates(true);

			SelectedTag stag = new SelectedTag(LibSVM.SVMTYPE_EPSILON_SVR,
					LibSVM.TAGS_SVMTYPE);
			svm.setSVMType(stag);
			SelectedTag ktag = new SelectedTag(LibSVM.KERNELTYPE_SIGMOID,
					LibSVM.TAGS_KERNELTYPE);
			svm.setKernelType(ktag);

			return (Classifier) svm;

		} else {
			try {
				Classifier classifier = (Classifier) Class.forName(classfierName).newInstance();
				if(classifier instanceof SVMreg){
					((SVMreg) classifier).setKernel(new NormalizedPolyKernel());
				}
				return classifier;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return null;
	}

	private int[][] getTerms(int docid, int id) {
		return this.t_tfs_cache != null ? this.t_tfs_cache[id] : directIndex
				.getTerms(docid);
	}

	private void train(Classifier model, int[] docids, double scores[],
			int resSize) {
		// 
		TIntIntHashMap map = new TIntIntHashMap();
		gnu.trove.TIntHashSet querySet = new TIntHashSet();

		int start = 0;
		fvNominalVal = new FastVector();

		// ///////////////////process query terms/////////////////////////
		String terms[] = this.request.getMatchingQueryTerms().getTerms();
		for (int i = 0; i < terms.length; i++) {
			LexiconEntry entry = lexicon.getLexiconEntry(terms[i]);
			if (entry == null) {
				continue;
			}
			querySet.add(entry.termId);
//			 map.put(entry.termId, start++);
//			 fvNominalVal.addElement(new Attribute("" + entry.termId));
		}
		// /////////////////////ordinary word
		// attribute//////////////////////////////////////
		TermsStatistics ts = new TermsStatistics();
		int t_tfs_cache[][][] = new int[positiveNum + negetiveNum][][];
		int t_tfs_all_cache[][][] = new int[resSize][][];
		int cache = 0;
		for (int i = 0; i < resSize; i++) {
			int t_tfs[][] = getTerms(docids[i], i);
			t_tfs_all_cache[i] = t_tfs;
			for (int k = 0; k < t_tfs[0].length; k++) {
				ts.insertTerm(t_tfs[0][k], t_tfs[1][k]);
				String stermid = "" + t_tfs[0][k];
			}
			if (i < positiveNum) {
				t_tfs_cache[cache++] = t_tfs;
			} else if (resSize - i <= negetiveNum) {
				t_tfs_cache[cache++] = t_tfs;
			}
		}
		// TermsStatistics.ExpansionTerm[] expTerms = ts.getExpandedTerms(maxN,
		// ts.tfComparator);

		// int max = expTerms.length;
		// for (int i = 0; i < max; i++) {
		// int id = expTerms[i].getTermID();
		// map.put(id, start++);
		// fvNominalVal.addElement(new Attribute("" + id));
		// }
		// ////////////////////////////////////////////////////////////////

		// ////////////////////////////////////////////////////////////////
		SingleTermQuery[] expTerms = expansionTerm.getExpandedTerms(
				maxNFeatures, QEModel);

		// ////////////////////////////////////////////////////////////////

		System.out.println("Feature Num: " + expTerms.length);
		for (int i = 0; i < expTerms.length; i++) {
			String term = expTerms[i].getTerm();
			lexicon.findTerm(term);
			int id = lexicon.getTermId();
			if (includeQueryTermTag) {
				map.put(id, start++);
				fvNominalVal.addElement(new Attribute("" + id));
			}
		}

		// add docLen feature
		docLenAtt = new Attribute("docLen");
		fvNominalVal.addElement(docLenAtt);
		// add Mitra Score feature
		mitraAtt = new Attribute("mitra");
		fvNominalVal.addElement(mitraAtt);

		// Attribute scoreAttribute = new Attribute("original_score");
		// fvNominalVal.addElement(scoreAttribute);

		// add class category feature
		/*
		 * FastVector fvClassVal = new FastVector(2);
		 * fvClassVal.addElement("positive"); fvClassVal.addElement("negative");
		 * Attribute ClassAttribute = new Attribute("theClass", fvClassVal);
		 * fvNominalVal.addElement(ClassAttribute);
		 */
		Attribute ClassAttribute = new Attribute("theClass");
		fvNominalVal.addElement(ClassAttribute);

		this.FutureSize = fvNominalVal.size();
		trainingSet = new Instances("Rel", fvNominalVal, FutureSize);
		double tmax = this.scores[0];
		for (int i = 0; i < positiveNum + negetiveNum; i++) {
			int doc_subscript;
			int docid;
			if (i < positiveNum) {
				doc_subscript = i;
				docid = docids[doc_subscript];
			} else {
				doc_subscript = resSize - positiveNum + (i - negetiveNum);
				docid = docids[doc_subscript];
			}
			int t_tfs[][] = t_tfs_cache[i];

			// make instance
			Instance example = makeInstance(t_tfs, map, docid, doc_subscript);
//			if (i < positiveNum) {
//				 example.setValue(ClassAttribute, "positive");
//			} else {
//				 example.setValue(ClassAttribute, "negative");
//			}

			example.setValue(ClassAttribute, this.scores[doc_subscript] / tmax);
			// example.setDataset(trainingSet);
			trainingSet.add(example);
		}

		trainingSet.setClass(ClassAttribute);

		try {
			model.buildClassifier(trainingSet);
			// Evaluation eval = new Evaluation(trainingSet);
			// eval.evaluateModel(model, trainingSet);
			// logger.info(eval.toSummaryString());
			// logger.info("classifer training finished");
		} catch (Exception e) {
			e.printStackTrace();
		}

		int postProSize = Math.min(this.reRankNum, resSize);

		ClassifierStat cstat = new ClassifierStat(postProSize, this.queryID);
		cstat.docids = Arrays.copyOfRange(docids, 0, postProSize);
		cstat.original_scores = Arrays.copyOfRange(scores, 0, postProSize);
		cstat.positiveNum = positiveNum;
		cstat.negetiveNum = this.negetiveNum;

		double normaliser = scores[0];
		for (int i = 0; i < postProSize; i++) {
			int docid = docids[i];
			int[][] t_tfs = t_tfs_all_cache[i];
			Instance example = makeInstance(t_tfs, map, docid, i);
			// logger.info("making " + i +" instance");
			example.setDataset(trainingSet);

			try {
				double cscore[] = model.distributionForInstance(example);
				String docno = this.documentIndex.getDocumentNumber(docid);
				cstat.c_scores[i] = cscore[0];
				cstat.docnos[i] = docno;
				// System.out.println(i + ": "+ cscore[0]+ ", " +cscore[1]);
				if (i < 0) {
					// scores[i] = cscore[0];
				} else {
					if (cscore[0] <= 0.0001) {
						scores[i] = Double.NEGATIVE_INFINITY;
						// System.out.println("NEGATIVE_INFINITY : " + i + ", "
						// + cscore[0]);
					} else {
						double tempD = scores[i] / normaliser;
						scores[i] = (1-k) * tempD + k * cscore[0];
//						System.out.println(tempD + "," + cscore[0]);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (CLTag)
			logger.info(cstat.getInfo());
	}

	private Instance makeInstance(int t_tfs[][], TIntIntHashMap map, int docid,
			int id) {
		int dlen = this.documentIndex.getDocumentLength(docid);

		Instance example = new Instance(this.FutureSize);
		int count = 0;
		for (int k = 0; k < t_tfs[0].length; k++) {
			if (map.contains(t_tfs[0][k])) {
				int pos = map.get(t_tfs[0][k]);
				count++;

				// LexiconEntry lEntry = lexicon.getLexiconEntry(t_tfs[0][k]);
				// wmodel.setKeyFrequency(1);
				// wmodel.setDocumentFrequency((double) lEntry.n_t);
				// wmodel.setTermFrequency((double) lEntry.TF);
				//
				// double tmps = wmodel.score(t_tfs[1][k], dlen);

				double tmps = this.qerPP.getScorce(t_tfs[0][k], t_tfs[1][k],
						dlen);
				example.setValue(pos, tmps); // todo: change the weighting
				// formula
			}
		}
		// add other features
		// docLenAtt
		example.setValue(docLenAtt, Math.abs(dlen
				- this.collStats.getAverageDocumentLength()));
		// mitra scores att
		if (Mitra_tag) {
			example.setValue(mitraAtt, this.mitraScores[id]);
		}

		for (int i = 0; i < this.FutureSize; i++) {
			if (example.isMissing(i)) {
				example.setValue(i, 0);
			}
		}

		return example;
	}

	static ArrayList<struct> listStruct = new ArrayList<struct>();

	class struct {
		double t_pP = 0, t_pR = 0, t_pF = 0, t_nP = 0, t_nR = 0, t_nF = 0;
		int Num = 0;
	}

	public class ClassifierStat {

		int positiveNum = 15;
		int negetiveNum = 15;
		int size;
		public final String docnos[];
		int docids[];
		double original_scores[];
		double c_scores[];
		String qid;

		public ClassifierStat(int size, String queryid) {
			this.size = size;
			this.docids = new int[size];
			this.original_scores = new double[size];
			this.c_scores = new double[size];
			this.docnos = new String[size];
			this.qid = queryid;
		}

		public String getRelInTopK(int start, int k) {

			int relC = 0;
			int clsR_R = 0; // classify right in relevance document
			int num_R = 0;
			int clsR_nR = 0;
			int num_nR = 0;

			int TT = 0, TF = 0, FF = 0, FT = 0;

			double pP = 0, pR = 0, nP = 0, nR = 0, pF = 0, nF = 0;

			for (int i = start; i < start + k; i++) {
				if (trecR.isRelevant(qid, docnos[i])) {
					num_R++;
					relC++;
					if (c_scores[i] > 0.5) {
						clsR_R++;
						TT++;
					} else {
						TF++;
					}
				} else {
					num_nR++;
					if (c_scores[i] <= 0.5) {
						clsR_nR++;
						FF++;
					} else {
						FT++;
					}
				}
				if (i == start + k - 1) {
					pP = TT / (double) (TT + FT + 0.0000001);
					pR = TT / (double) (TT + TF + 0.0000001);
					nP = FF / (double) (FF + TF + 0.0000001);
					nR = FF / (double) (FF + FT + 0.0000001);
					pF = 2 * pP * pR / (pP + pR + 0.0000001);
					nF = 2 * nP * nR / (nP + nR + 0.0000001);
					if (start == positiveNum) {

						if (listStruct.size() == 0) {
							struct s = new struct();
							s.t_nF = nF;
							s.t_nP = nP;
							s.t_nR = nR;
							s.t_pF = pF;
							s.t_pP = pP;
							s.t_pR = pR;
							s.Num++;
							listStruct.add(s);
						} else {
							struct s = listStruct.get(0);
							s.t_nF += nF;
							s.t_nP += nP;
							s.t_nR += nR;
							s.t_pF += pF;
							s.t_pP += pP;
							s.t_pR += pR;
							s.Num++;
						}
					}
				}
			}

			// String retStr = "from " + start + " to " + (start + k)
			// + " : Num of R: " + trecR.getNumberOfRelevant(qid) + " ,"
			// + "organic relC precision: " + relC / (double) k + " , "
			// + clsR_R / (double) num_R + " , " + clsR_nR
			// / (double) num_nR;

			String retStr = "from " + start + " to " + (start + k)
					+ " :total Num of R: " + trecR.getNumberOfRelevant(qid)
					+ " , the Num : " + num_R
					+ "\t\n in Positive,  precision: " + pP + ", recall: " + pR
					+ ", F: " + pF + ", " + "\t\n in Negetive,  precision: "
					+ nP + ", recall: " + nR + ", F: " + nF;

			if (start == positiveNum && (qid.equals("28") || qid.equals("54"))) {
				struct s = listStruct.get(0);
				String aver = "\nAverager : " + s.Num
						+ "\t\t\n in Positive,  precision: " + s.t_pP / s.Num
						+ ", recall: " + s.t_pR / s.Num + ", F: " + s.t_pF
						/ s.Num + ", " + "\t\t\n in Negetive,  precision: "
						+ s.t_nP / s.Num + ", recall: " + s.t_nR / s.Num
						+ ", F: " + s.t_nF / s.Num;
				retStr += aver;
			}
			return retStr;
		}

		public String getInfo() {
			StringBuilder buf = new StringBuilder();
			buf.append(getRelInTopK(0, positiveNum) + "\n");
			buf.append(getRelInTopK(positiveNum, 15) + "\n");
			buf.append(getRelInTopK(30, size / 2 - 30) + "\n");
			buf.append(getRelInTopK(size / 2, size / 2 - positiveNum) + "\n");
			buf.append(getRelInTopK(size - positiveNum, positiveNum) + "\n");
			return buf.toString();
		}

	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		// 1. the ratio of relevant in top 50 k
		// 2. the ratio of in the range of 15-30
	}

}