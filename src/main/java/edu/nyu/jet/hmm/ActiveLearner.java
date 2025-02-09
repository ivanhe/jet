// -*- tab-width: 4 -*-
package edu.nyu.jet.hmm;

import java.util.*;
import java.io.*;
import edu.nyu.jet.tipster.*;
import edu.nyu.jet.lisp.FeatureSet;
import edu.nyu.jet.lex.Tokenizer;
import edu.nyu.jet.zoner.SentenceSplitter;
import edu.nyu.jet.scorer.*;

/*
 *  ActiveLearner provides a system for the active learning of
 *  an HMM-based named entity tagger.  It can support either
 *  simulated active learning or true active learning.  
 *
 *  When used for simulated active learning, it takes as argument a
 *  single fully-tagged document collection. We divide this 
 *  collection into three parts:
 *    initialTrainingSet  [fully annotated, serves as seed]
 *    activeLearningSet   [annotated incrementally]
 *    testSet
 *  In active learning, selection of the next item to be labeled is
 *  done at the sentence level and is based on the margin:  the
 *  difference in the probability of the top two analyses of
 *  the sentence using the HMM.
 *
 *  Sentences included in training are marked with the feature "training"
 *  with value "true" on the SENTENCE annotation.
 */

public class ActiveLearner {

	static HMMNameTagger nt;

	static String[] tagsToRead = {"ENAMEX", "TIMEX", "NUMEX"};
    // sizes for full ACE corpus (600 documents
	// static final int initialTrainingSetSize = 50;
	// static final int testSetSize = 50;
	static final int initialTrainingSetSize = 10;
	static final int testSetSize = 10;
	// activeTraining:
	//    if true, select sentences with smallest margin
	//    if false, select sentences at random
	static final boolean activeTraining = true;
	static final boolean simulatedTraining = false;
	static final boolean multithread = false;
	static final int sentencesPerSweep = 5;
	static ArrayList<SentenceWithMargin> sentencesWithSmallestMargin;
	static ArrayList<SentenceWithMargin> sentencesToAnnotate;
	static ArrayList<Document> documentsBeingAnnotated = new ArrayList<Document>();
	static InteractiveAnnotator annotationThread = null;
	// poolSentences:
	//     if activeTraining = false, collects candidates for annotation
	//     (sentences not yet annotated)
	static ArrayList<SentenceWithMargin> poolSentences;
	// keepLearning:  set to false by user through annotationTool to quit learning
	static public volatile boolean keepLearning = true;
	// number of (unannotated) sentences in active training pool
	static int sentencesInPool = 0;
	static DocumentCollection col;
	static PrintWriter logFile = null;

	public static void main (String[] args) throws IOException {
        String home = System.getProperty("jetHome");
		String logFileName = home + "active.log";
		logFile = new PrintWriter(new BufferedWriter(new FileWriter(logFileName)));

        initialize();
        for (int rep=0; rep<=100; rep+=sentencesPerSweep) {
            learn();
            if (!keepLearning) break;
        }
        logFile.close();
	}

	static void initialize () {

		// load collection with NE annotation

		// split documents into sentences and tokenize them
		col.open();
		for (int i=0; i<col.size(); i++) {
			ExternalDocument doc = col.get(i);
			// doc.setSGMLtags (tagsToRead);
			doc.setAllTags(true);
			doc.open();
            doc.stretchAll();
			// split document into sentences
			// doc.annotateWithTag ("text");
			Vector<Annotation> textSegments = doc.annotationsOfType ("TEXT");
		    Iterator<Annotation> it = textSegments.iterator ();
		    while (it.hasNext ()) {
		        Annotation ann = it.next ();
		        Span textSpan = ann.span ();
		        SentenceSplitter.split (doc, textSpan);
		        Vector<Annotation> sentences = doc.annotationsOfType ("sentence");
		    	if (sentences == null) continue;
		    	Iterator<Annotation> is = sentences.iterator ();
	        	while (is.hasNext ()) {
		        	Annotation sentence = is.next ();
		        	Span sentenceSpan = sentence.span();
		        	Tokenizer.tokenize (doc, sentenceSpan);
				}
		    }
		}
		// mark sentences in initialTraining documents
		int initialTrainingSentenceCount = 0;
		for (int i=0; i<initialTrainingSetSize; i++) {
			Document doc = col.get(i);
			Vector<Annotation> sentences = doc.annotationsOfType ("sentence");
	    if (sentences == null) continue;
	    Iterator<Annotation> is = sentences.iterator ();
      	while (is.hasNext ()) {
        	Annotation sentence = is.next ();
        	sentence.put("training", "true");
        	initialTrainingSentenceCount++;
        }
	  }
	  System.out.println (initialTrainingSentenceCount + " sentences in initial training set");

		// copy ENAMEX to TRUENAMEX throughout, and
		// erase ENAMEX except in initialTrainingSet
		for (int i=0; i<col.size(); i++) {
			Document doc = col.get(i);
			Vector<Annotation> enamexList = doc.annotationsOfType ("ENAMEX");
		    if (enamexList == null) continue;
		    Iterator<Annotation> is = enamexList.iterator ();
        	while (is.hasNext ()) {
	        	Annotation enamex = is.next ();
	        	doc.annotate("TRUENAMEX", enamex.span(), enamex.attributes());
	        	if (i >= initialTrainingSetSize) doc.removeAnnotation(enamex);
	        }
	    }

		//    train HMM on initial training set

		nt = new HMMNameTagger(WordFeatureHMMemitter.class);
		nt.buildNameHMM("data/ACEnameTags.txt");
		if (activeTraining) nt.nameHMM.recordMargin();

		for (int i=0; i<col.size(); i++) {
			Document doc = col.get(i);
			nt.nameHMM.newDocument();
			Vector<Annotation> sentences = doc.annotationsOfType ("sentence");
		    if (sentences == null) continue;
		    Iterator<Annotation> is = sentences.iterator ();
        	while (is.hasNext ()) {
	        	Annotation sentence = (Annotation)is.next ();
	        	if (sentence.get("training") == null) continue;
	        	Span sentenceSpan = sentence.span();
	        	nt.annotator.trainOnSpan (doc, sentenceSpan);
			}
		}
		nt.nameHMM.computeProbabilities();
	}

	static void learn () {
		int sentencesAnnotated = 0;

		//  annotate non-training sentences
		//
		//  maintain in 'sentencesWithSmallestMargin' the 'sentencesPerSweep'
		//  sentences which have the smallest margins

		sentencesInPool = 0;
		sentencesWithSmallestMargin = new ArrayList<SentenceWithMargin>(sentencesPerSweep);
		if (!activeTraining) poolSentences = new ArrayList<SentenceWithMargin>();
		double maxSmallestMargin = 0.;
		for (int i=0; i<col.size(); i++) {
			if (!keepLearning) break;
			Document doc = col.get(i);
			if (documentsBeingAnnotated.contains(doc)) continue;
			nt.nameHMM.newDocument();
			Vector<Annotation> sentences = doc.annotationsOfType ("sentence");
			if (sentences == null) continue;
			Iterator<Annotation> is = sentences.iterator ();
			while (is.hasNext ()) {
				Annotation sentence = (Annotation)is.next ();
				if (sentence.get("training") != null) continue;
				Span sentenceSpan = sentence.span();
				nt.annotator.annotateSpan (doc, sentenceSpan);
				if (activeTraining) {
					double margin = nt.nameHMM.getMargin();
					if (sentencesWithSmallestMargin.size() < sentencesPerSweep) {
						sentencesWithSmallestMargin.add(new SentenceWithMargin
						  (doc, sentence, margin));
						if (maxSmallestMargin < margin) {
							maxSmallestMargin = margin;
						}
					} else if (margin < maxSmallestMargin) {
						SentenceWithMargin x =
						  (SentenceWithMargin) Collections.max(sentencesWithSmallestMargin);
						sentencesWithSmallestMargin.remove(x);
						sentencesWithSmallestMargin.add(new SentenceWithMargin
							(doc, sentence, margin));
						SentenceWithMargin y =
							(SentenceWithMargin) Collections.max(sentencesWithSmallestMargin);
						maxSmallestMargin = y.margin;
					}
				} else {
					poolSentences.add(new SentenceWithMargin(doc, sentence, 0.0));
				}
				sentencesInPool++;
			}
		}

		//    score

		// if (rep == 1 || rep%5 == 0) {
		int tagsInResponses = 0;
		int tagsInKeys = 0;
		int matchingTags = 0;
		int matchingAttrs = 0;
		for (int i=col.size()-testSetSize; i<col.size(); i++) {
			Document doc = col.get(i);
			SGMLScorer scorer = new SGMLScorer(doc, doc);
			scorer.match("TRUENAMEX", "ENAMEX");
			tagsInResponses += scorer.totalTagsInDoc1;
			tagsInKeys      += scorer.totalTagsInDoc2;
			matchingTags    += scorer.totalMatchingTags;
			matchingAttrs   += scorer.totalMatchingAttrs;
		}
	     System.out.println ("Overall Type Recall:          " +
	     	(float) matchingTags / tagsInKeys);
	     System.out.println ("Overall Type Precision:       " +
	     	(float) matchingTags / tagsInResponses);
	     System.out.println ("Overall Attribute Recall:     " +
	     	(float) matchingAttrs / tagsInKeys);
	     System.out.println ("Overall Attribute Precision:  " +
	     	(float) matchingAttrs / tagsInResponses);
	     if (logFile != null)
		     logFile.println (sentencesAnnotated + ", " + (float) matchingAttrs / tagsInKeys
		                      + ", " + (float) matchingAttrs / tagsInResponses);

	     if (matchingAttrs == tagsInKeys) {
             System.out.println("100% recall reached, terminating.");
             System.exit(0);
         }

			// erase ENAMEX annotations in non-training sentences

			for (int i=0; i<col.size(); i++) {
			Document doc = col.get(i);
			Vector<Annotation> sentences = doc.annotationsOfType ("sentence");
				if (sentences == null) continue;
				Iterator<Annotation> is = sentences.iterator ();
				while (is.hasNext ()) {
					Annotation sentence = (Annotation)is.next ();
					if (sentence.get("training") != null) continue;
					Span sentenceSpan = sentence.span();
					eraseAnnotationsInside (doc, "ENAMEX", sentenceSpan);
				}
			}

		// in multithreaded system, wait for annotation thread to
		// complete and update HMM using new annotations

		if (annotationThread != null) {
			try {
				if (annotationThread.isAlive())
					System.out.println ("Waiting for annotation thread.");
				annotationThread.join();
				System.out.println ("Annotation thread finished.");
			} catch (InterruptedException e) {
				System.out.println (e);
			}
			for (int k=0; k<sentencesToAnnotate.size(); k++) {
				SentenceWithMargin swm =
						(SentenceWithMargin) sentencesToAnnotate.get(k);
				nt.annotator.trainOnSpan (swm.document, swm.sentence.span());
				sentencesAnnotated++;
			}
			nt.nameHMM.computeProbabilities();
		}

		if (!keepLearning) return;

		//  select sentences to annotate

		//  activeLearner:
		//    add sentences with lowest margin to training set
		//  randomLearner:
		//    add random sentences to training set

		if (activeTraining) {
			sentencesToAnnotate = new ArrayList<SentenceWithMargin>(sentencesWithSmallestMargin);
		} else {
			sentencesToAnnotate = new ArrayList<SentenceWithMargin>();
			for (int k=0; k<sentencesPerSweep; k++) {
				int isent = (int) (poolSentences.size() * Math.random());
				SentenceWithMargin swm =
					(SentenceWithMargin) poolSentences.get(isent);
				sentencesToAnnotate.add(swm);
				poolSentences.remove(swm);
			}
		}

		// identify documents containing these sentences

		if (multithread) {
			documentsBeingAnnotated = new ArrayList<Document>();
			for (int k=0; k<sentencesToAnnotate.size(); k++) {
				SentenceWithMargin swm =
						(SentenceWithMargin) sentencesToAnnotate.get(k);
				documentsBeingAnnotated.add(swm.document);
			}
		}

		// annotate selected sentences and update HMM

		if (simulatedTraining) {
			for (int k=0; k<sentencesToAnnotate.size(); k++) {
				SentenceWithMargin swm =
						(SentenceWithMargin) sentencesToAnnotate.get(k);
				addToTraining (swm.document, swm.sentence);
				sentencesAnnotated++;
			}
			nt.nameHMM.computeProbabilities();
		} else if (multithread) {
			annotationThread = new InteractiveAnnotator(sentencesToAnnotate);
			annotationThread.setPriority(Thread.NORM_PRIORITY + 1);
			annotationThread.start();
			System.out.println ("*** initiated annotation Thread ***");
		} else {
			for (int k=0; k<sentencesToAnnotate.size(); k++) {
				SentenceWithMargin swm =
						(SentenceWithMargin) sentencesToAnnotate.get(k);
				InteractiveAnnotator.annotate (swm.document, swm.sentence);
				nt.annotator.trainOnSpan (swm.document, swm.sentence.span());
				sentencesAnnotated++;
			}
			nt.nameHMM.computeProbabilities();
		}
	}

	@SuppressWarnings("unchecked")
	private static void eraseAnnotationsInside (Document doc, String type, Span span) {
		Vector<Annotation> v = doc.annotationsOfType(type);
		if (v == null) return;
		v = (Vector<Annotation>) v.clone();
		for (int i=0; i<v.size(); i++) {
			Annotation a = (Annotation) v.get(i);
			if (a.span().within(span)) {
				doc.removeAnnotation(a);
			}
		}
	}

	private static void addToTraining (Document doc, Annotation sentence) {
		Span span = sentence.span();
		System.out.println ("Now annotating:");
		System.out.println (doc.text(sentence));
		// if (simulatedTraining) {
			int start = span.start();
			int end = span.end();
			for (int i=start; i<end; i++) {
				Vector<Annotation> enamexList = doc.annotationsAt (i, "TRUENAMEX");
				if (enamexList == null) continue;
				Iterator<Annotation> is = enamexList.iterator ();
				while (is.hasNext ()) {
					Annotation enamex = (Annotation)is.next ();
					doc.annotate("ENAMEX", enamex.span(), enamex.attributes());
				}
			}
		// } else {
		//	AnnotationTool tool;
		//	tool = new AnnotationTool();
	  //	tool.addType ('p', new Annotation ("ENAMEX", null, new FeatureSet("TYPE", "PERSON")));
	  //	tool.addType ('o', new Annotation ("ENAMEX", null, new FeatureSet("TYPE", "ORGANIZATION")));
	  //	tool.addType ('g', new Annotation ("ENAMEX", null, new FeatureSet("TYPE", "GPE")));
		//	tool.annotateDocument(doc, span);
		// }
		sentence.put("training", "true");
		nt.annotator.trainOnSpan (doc, span);
	}
}

/**
 *  A tool for editing annotations on a Document, currently
 *  implemented as a wrpper on the JET AnntationTool.
 */

class InteractiveAnnotator extends Thread {
	ArrayList<SentenceWithMargin> sentencesToAnnotate;

	InteractiveAnnotator (ArrayList<SentenceWithMargin> sentences) {
		sentencesToAnnotate = sentences;
	}

	@Override
	public void run () {
		for (int isent=0; isent<sentencesToAnnotate.size(); isent++) {
			SentenceWithMargin swm =
					(SentenceWithMargin) sentencesToAnnotate.get(isent);
			annotate (swm.document, swm.sentence);
			if (!ActiveLearner.keepLearning) return;
		}
	}

    /**
     *  Add annotations to span 'sentence' of Document 'doc'
     *  using the JET AnnotationTool.
     */

	static void annotate (Document doc, Annotation sentence) {
		AnnotationTool tool;
		tool = new AnnotationTool();
        tool.addType ('p', new Annotation ("ENAMEX", null, new FeatureSet("TYPE", "PERSON")));
        tool.addType ('o', new Annotation ("ENAMEX", null, new FeatureSet("TYPE", "ORG")));
        tool.addType ('l', new Annotation ("ENAMEX", null, new FeatureSet("TYPE", "LOCATION")));
        boolean quit = tool.annotateDocument(doc, sentence.span());
        if (quit)
			ActiveLearner.keepLearning = false;
        sentence.put("training", "true");
    }
}

/**
 *  a sentence, along with the Document containing it and the margin
 *  between the top two analyses of the sentence using the HMM
 */

class SentenceWithMargin implements Comparable<Object> {
	Document document;
	Annotation sentence;
	double margin;

	SentenceWithMargin (Document d, Annotation s, double m) {
		document = d;
		sentence = s;
		margin = m;
	}

	/**
	 *  compares sentences based on their margin.
	 *  Note: this produces an ordering that is inconsistent with equals,
	 *  so SentenceWithMargin objects cannot be put in ordered collections
	 *  such as TreeSets.
	 */

	public int compareTo (Object o) {
		SentenceWithMargin s2 = (SentenceWithMargin) o;
		if (margin < s2.margin)
			return -1;
		else if (margin > s2.margin)
			return 1;
		else
			return 0;
	}
}
