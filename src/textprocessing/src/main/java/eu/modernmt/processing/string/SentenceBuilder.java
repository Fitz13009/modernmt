package eu.modernmt.processing.string;

import eu.modernmt.RuntimeErrorException;
import eu.modernmt.lang.Language;
import eu.modernmt.model.*;
import eu.modernmt.processing.ProcessingException;
import eu.modernmt.processing.detokenizer.jflex.JFlexDetokenizer;
import eu.modernmt.processing.detokenizer.jflex.JFlexSpaceAnnotator;
import eu.modernmt.processing.detokenizer.jflex.SpacesAnnotatedString;
import eu.modernmt.processing.tags.XMLCharacterEntity;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Created by andrearossi on 22/02/17.
 * <p>
 * A SentenceBuilder handles most of the preprocessing activities of a string
 * and generates a Sentence object with the resulting Tokens.
 * <p>
 * The SentenceBuilder stores both
 * - the original version of the String, which is never altered
 * - the current version of the String, that can undergo changes (and is therefore implemented as a StringBuilder)
 * <p>
 * In order to perform String processing the SentenceBuilder employs one Editor,
 * that can update the current string by creating and committing Transformations.
 * The SentenceBuilder also has a Transformation list all Editors add their Transformations to when executing commit.
 * <p>
 * Moreover, the SentenceBuilder has a reference to a IndexMap object
 * that for each position on the local version of the string in the current Editor
 * contains the position on the correspondent character on the original string.
 * <p>
 * During the queue of the build() method the SentenceBuilder
 * uses Transformations to create Tokens, that are employed to generate a Sentence.
 * <p>
 * In order to save memory and time, during all preprocessing activities for all strings
 * one and only one SentenceBuilder object is used.
 * After the generation of the Sentence for the current string it is just cleared and re-initialized.
 */
public class SentenceBuilder {

    private static final Pattern NOT_WHITESPACE = Pattern.compile("[^\\p{javaWhitespace}]");

    private final JFlexSpaceAnnotator annotator;

    private String originalString = null; // the original string to tokenize
    private final HashSet<Annotation> annotations = new HashSet<>();
    private final List<Transformation> transformations = new ArrayList<>(); // ordered list of committed transformations
    private final StringBuilder currentString = new StringBuilder(); // latest string modified by transformations
    private final IndexMap indexMap = new IndexMap(); // map between indexes from currentString to originalString

    private final Editor editor = new Editor();

    /**
     * This constructor generates an empty SentenceBuilder
     * and performs initialization for a given string.
     *
     * @param language the language of this sentence builder
     * @param string   the original string that must be processed and tokenized
     */
    public SentenceBuilder(Language language, String string) {
        this(language);
        this.initialize(string);
    }

    /**
     * This constructor generates an empty SentenceBuilder,
     * with no information about the strings it is going to process.
     * <p>
     * This method should only be called by the Preprocessor object
     * once, at the beginning of its lifecycle, to create
     * the single SentenceBuilder instance that it will employ
     *
     * @param language the language of this sentence builder
     */
    public SentenceBuilder(Language language) {
        this.annotator = JFlexDetokenizer.newAnnotator(language);
    }

    /**
     * This method initializes a SentenceBuilder object
     * with information about one string that must be processed.
     *
     * @param string the original string that must be processed
     */
    public SentenceBuilder initialize(String string) {
        this.originalString = string;

        /*at the beginning no transformations have been performed*/
        this.currentString.setLength(0);
        this.currentString.append(string);

        /*list of transformation lists generated by editors*/
        this.transformations.clear();

        /*initialize indexMap array that maps each position of the current string
         * to a position in the original string*/
        this.indexMap.initialize(originalString.length());

        /*collection of annotations*/
        this.annotations.clear();

        return this;
    }

    /**
     * This method resets the SentenceBuilder instance to
     * its initial state, and makes it ready to
     * be initialized again with another string to process.
     */
    public void clear() {
        this.originalString = null;

        /*at the beginning no transformations have been performed*/
        this.currentString.setLength(0);

        /*list of transformation lists generated by editors*/
        this.transformations.clear();

        /*collection of annotations*/
        this.annotations.clear();
    }

    public void addAnnotation(Annotation annotation) {
        this.annotations.add(annotation);
    }

    /**
     * Method that returns the Editor to process the current string version,
     * if it is not already in use.
     *
     * @return this SentenceBuilder editor
     */
    public Editor edit() {
        return this.editor.init();
    }

    /**
     * Method that scans all transformations committed by the editor;
     * it selects the non-in-conflict transformations with highest priority,
     * uses them to generate tokens (words and tags)
     * that are finally employed to create a Sentence object
     *
     * @return the Sentence resulting from the transformations on the original string
     */
    public Sentence build() {
        List<Transformation> tokenizableTransformations = this.getTokenizableTransformations();

        Sentence sentence = tokenize(originalString, tokenizableTransformations);
        if (!annotations.isEmpty())
            sentence.addAnnotations(annotations);

        return computeRequiredSpaces(sentence);
    }

    private Sentence computeRequiredSpaces(Sentence sentence) {
        SpacesAnnotatedString text = SpacesAnnotatedString.fromSentence(sentence);

        annotator.reset(text.getReader());
        int type;
        while ((type = next(annotator)) != JFlexSpaceAnnotator.YYEOF) {
            annotator.annotate(text, type);
        }

        text.applyLeft(sentence, Word::setLeftSpaceRequired);
        text.applyRight(sentence, Word::setRightSpaceRequired);
        return sentence;
    }

    private static int next(JFlexSpaceAnnotator annotator) {
        try {
            return annotator.next();
        } catch (IOException e) {
            throw new RuntimeErrorException("IOException thrown by CharArrayReader", e);
        }
    }

    /**
     * Method that scans all the Transformations committed by the editor
     * and selects those that can be employed to generate tokens.
     * In case of conflict between two or more Transformations,
     * the Transformations are resolved adjusting the start and end position of the lower priority ones.
     * The resulting Transformation list is sorted by increasing start position.
     * <p>
     * Note: replacements are considered as non tokenizable Transformations
     *
     * @return the list of high-priority, resolved transformations,
     * sorted by their start position on the original string
     */
    private List<Transformation> getTokenizableTransformations() {
        /*Create a bitset with as many bits as the positions in originalString
         * the bitset is employed to remember, for each position in the original string,
         * whether the corresponding character has been altered by a transformation or not*/
        BitSet bitset = new BitSet(this.originalString.length());

        StringBuilder originalText = new StringBuilder();
        List<Transformation> result = new ArrayList<>();

        for (Transformation transformation : transformations) {
            if (transformation.tokenFactory == null)  // skip simple replacements
                continue;

            originalText.setLength(0);
            int start = -1;
            int end = transformation.end;

            // Iterate over positions covered by the transformation and adjust its start and end position
            // in order to avoid overlapping with higher-priority tokens
            for (int i = transformation.start; i < transformation.end; i++) {
                if (!bitset.get(i)) { // position is available
                    if (start < 0) start = i;
                    bitset.set(i, true);
                    originalText.append(this.originalString.charAt(i));
                } else {
                    if (start >= 0 && end > i) end = i;
                }
            }

            if (start < 0) // not even one char is available
                continue;

            transformation.start = start;
            transformation.end = end;
            transformation.originalText = originalText.toString();
            result.add(transformation);
        }

        result.sort(Comparator.comparingInt(o -> o.start));  // sort by increasing start position
        return result;
    }


    /**
     * Method that scans over all the tokenizable Transformations and
     * generates a Token object for each of them.
     *
     * @param transformations the list of all tokenizable Transformations,
     *                        sorted by increasing start position
     * @return a TokenSet containing a separate list of Tokens
     * for each Token type generated (e.g. words, tags, etc).
     */
    private static Sentence tokenize(String string, List<Transformation> transformations) {
        char[] chars = string.toCharArray();

        List<Word> words = new ArrayList<>(transformations.size());
        List<Tag> tags = new ArrayList<>(transformations.size());

        for (int i = 0; i < transformations.size(); i++) {
            Transformation transformation = transformations.get(i);

            /*extract the necessary information from the transformation*/
            String placeholder = transformation.text;
            TokenFactory tokenFactory = transformation.tokenFactory;

            /*compute additional information about the way that
             the current transformation is linked to the previous and next ones in the list*/

            /*true if previous and current tokens have different type (Tag-Word or Word-Tag)*/
            boolean hasHiddenLeftSpace = false;
            /*true if previous and current tokens have different type (Tag-Word or Word-Tag)*/
            boolean hasHiddenRightSpace = false;

            /*string with the space between previous transformation text and current one*/
            String leftSpace = null;
            /*string with the space between current transformation text and next one*/
            String rightSpace = null;
            /*amount of WORDS that occur before the current transformation*/
            int tagPosition;

            /*LeftSapce and RightSpace can only be extracted by the original string,
                as the transformation indexes refer to positions in the original string.
                However the original string still has
                    - xml tags
                    - xml escaping sequences (e.g: &lt;, &gr;, &nbsp;, etc).
                    - rare chars
                    - whitespaces
                    - etc
                XML tags lead to the creation of new Tag Transformations that are now in the
                tokenizable transformations list, so an XML tag can't be in a leftSpace or a rightSpace

                Xml escaping sequences, rare chars and whitespaces on the contrary
                generate Replacement Transformations, that are not tokenizable
                so are not in the tokenizable list.
                While we are ok with having rarechars and whitespaces in leftSpace or rightSpace,
                we still don't want XML escaping sequences.
                Therefore we unescape the leftSpace or a rightSpace.
            */

            /*compute leftSpace*/
            int fromPosition;
            int length;
            if (i == 0) {
                /*if the current transformation is the first one in the list,
                 * it has a leftspace if it doesn't start at position 0 */
                fromPosition = 0;
                length = transformation.start - fromPosition;
            } else {
                /*the current transformation is not the first one in the list
                 * it has a leftSpace if it doesn't start at the end of its predecessor*/
                Transformation previousTransformation = transformations.get(i - 1);
                fromPosition = previousTransformation.end;
                length = transformation.start - fromPosition;
                if (length == 0) {
                    if ((transformation.tokenFactory == TokenFactory.WORD_FACTORY && previousTransformation.tokenFactory != TokenFactory.WORD_FACTORY) ||
                            (transformation.tokenFactory != TokenFactory.WORD_FACTORY && previousTransformation.tokenFactory == TokenFactory.WORD_FACTORY)) {
                        hasHiddenLeftSpace = true;
                    }
                }
            }

            if (length > 0)
                leftSpace = extractWhitespace(new String(chars, fromPosition, length));

            /*compute hasRightSpace and rightSpace*/
            if (i == transformations.size() - 1) {
                /*if the current transformation is the last one in the list*/
                fromPosition = transformation.end;
                length = chars.length - fromPosition;
            } else {
                /*if the current transformation is not the last one in the list*/
                Transformation nextTransformation = transformations.get(i + 1);
                fromPosition = transformation.end;
                length = nextTransformation.start - fromPosition;

                if (length == 0) {
                    if ((transformation.tokenFactory == TokenFactory.WORD_FACTORY && nextTransformation.tokenFactory != TokenFactory.WORD_FACTORY) ||
                            (transformation.tokenFactory != TokenFactory.WORD_FACTORY && nextTransformation.tokenFactory == TokenFactory.WORD_FACTORY)) {
                        hasHiddenRightSpace = true;
                    }
                }
            }

            if (length > 0)
                rightSpace = extractWhitespace(new String(chars, fromPosition, length));

            /*compute tagPosition*/
            /*the current tag position is the amount of words in the words list*/
            tagPosition = words.size();

            /*the original text is necessary to create the Token.
             However
                - If the token is a Tag, it surely does not require XML escaping
                - If it is a word, the original text may still contain
                       xml tags, xml escape sequences, rarechars and whitespaces
                       However it is impossible to have XML tags (they would lead to a Tag Token)
                       We are ok with whitespaces and rarechars
                       We still need xml escaping
                  Therefore, if we are creating a Word Token, unescape the originalText.
                 */
            if (tokenFactory == TokenFactory.WORD_FACTORY)
                transformation.originalText = XMLCharacterEntity.unescapeAll(transformation.originalText);

            /*generate the Token*/
            Token token = tokenFactory.build(transformation.originalText, placeholder, leftSpace, rightSpace, tagPosition);
            if (token instanceof  Word) {
                ((Word) token).setHiddenLeftSpace(hasHiddenLeftSpace);
                ((Word) token).setHiddenRightSpace(hasHiddenRightSpace);
            }
            /*put the token in the separate list corresponding to its class*/
            if (token instanceof Tag) {
                tags.add((Tag) token);
            } else if (token instanceof Word) {
                words.add((Word) token);
            }
        }

        return new Sentence(words.toArray(new Word[0]), tags.toArray(new Tag[0]));
    }

    private static String extractWhitespace(String string) {
        string = XMLCharacterEntity.unescapeAll(string);
        string = NOT_WHITESPACE.matcher(string).replaceAll("");
        return string.isEmpty() ? null : string;
    }


    /*getters and setters*/

    public String getOriginalString() {
        return this.originalString;
    }

    @Override
    public String toString() {
        return this.currentString.toString();
    }

    public char[] toCharArray() {
        int l = currentString.length();
        char[] buffer = new char[l];
        currentString.getChars(0, l, buffer, 0);

        return buffer;
    }


    /**
     * An Editor is an object that scans the SentenceBuilder current version of the string
     * in order to perform processing activities
     * and create corresponding Transformations to keep track of them.
     * <p>
     * Given one SentenceBuilder, its editor is always one and the same,
     * and it can't accept requests from more clients at a time.
     * The editor is "freed" from its client when it is asked to perform commit,
     * and it submits all the Transformations it has created to the SentenceBuilder.
     * It is then ready to accept another client.
     * <p>
     * An Editor can create Transformations to
     * - generate Tokens like WORDS and TAGS;
     * - to perform string replacements on the SentenceBuilder current String.
     * If a Transformation involves Token generation it may or may not involve replacements;
     * however, a Transformation may also be a simple replacement without Tokens to generate.
     * <p>
     * Token generation is executed by the SentenceBuilder when it runs its build() method.
     * On the contrary, replacements should be performed directly by the Editor
     * before the next client gets to see the currentString.
     * Therefore, during commit(), the Editor scans its newly generated Transformations
     * and if they involve replacements it applies them to the current String.
     * <p>
     * During the queue of the commit method, the Editor
     * - scans all the transformations in the local list
     * - applies replacements to the current String
     * - updates the start and end indexes of the transformation,
     * to make them match the right position in the original string, not the current one
     * - adds all the transformations to the SenteceBuilder transformations list
     * - gets ready for serving a new client.
     */
    public class Editor {
        private final List<Transformation> localTransformations = new ArrayList<>();
        private boolean inUse = false;

        /**
         * Constructor for the Editor for this SenteceBuilder;
         * Since the Editor is a singleton, this method is only used once.
         */
        public Editor() {
        }

        /**
         * Method to initialize the Editor:
         * if the Editor is not already in use,
         * it gets ready to serve a new client
         * and updates its state to "in use".
         * <p>
         * Otherwhise, it throws an IllegalStateException.
         *
         * @return a reference to the Editor itself, now marked as in use.
         */
        private Editor init() {

            if (this.inUse) {
                throw new IllegalStateException("this Editor is already in use");
            }

            this.localTransformations.clear();
            this.inUse = true;

            return this;
        }

        /**
         * This method handles a string processing requested to the Editor.
         * It includes the start and end indexes of the target text on the current String,
         * a replacement string (null is no replacement is involved in the Transformation),
         * and a reference to the token factory to use during build (null for simple replacements).
         * <p>
         * The Editor now proceeds to create a Transformation object
         * with indexes referring to the currentString in the SentenceBuilder.
         * and stores it in the Transformation in a local list.
         * <p>
         * Potential replacements are not executed contextually to the SetTransformation method;
         * they are handled during the commit method instead.
         * <p>
         *
         * @param currentStart first position of the text to edit in the current String
         * @param length       length of the text to edit
         * @param replacement  string that must substitute the text to edit.
         *                     If no replacement is needed, this parameter is null.
         * @param factory:     object that can generate tokens; depending on the kind of transformation
         *                     it can be specialized for words, tags, etc.
         *                     If no token must be generated by this transformation, this parameter is null.
         * @throws UnsupportedOperationException if the requested processing involves a replacement with ''
         *                                       in the middle of the string
         */
        private void setTransformation(int currentStart, int length, String replacement, TokenFactory factory) {

            /*the end of the text that is target to this Transformation*/
            int currentEnd = currentStart + length;

            /*check if the transformation involves an empty replacement in the middle of the string*/
            if (replacement != null && replacement.length() == 0) {
                if (!(currentStart == 0 || currentEnd == currentString.length()))
                    throw new UnsupportedOperationException("Empty replacements not yet supported in the middle of the sencence");
            }

            /*the text that is target to this transformation*/
            String text = currentString.substring(currentStart, currentEnd);

            /*create the transformation using the positions in the original string*/
            Transformation transformation = new Transformation(
                    currentStart,
                    currentEnd,
                    text,
                    replacement,
                    factory);

            this.localTransformations.add(transformation);
        }

        /**
         * This method handles the specific request of a simple replacement Transformation.
         * Therefore it just invokes the setting of a new Transformation object
         * sending it a TokenFactory field equal to null.
         * <p>
         * Since we are explicitly generating a replacement, the replacement field cannot be null.
         *
         * @param curStartIndex first position of the text to edit in the current string.
         * @param textLength    length of the text to process.
         * @param replacement   string that must substitute the text to edit. It can not be null.
         */
        public void replace(int curStartIndex, int textLength, String replacement) {
            if (replacement == null)
                throw new IllegalArgumentException("when invoking replace, the replacement must not be null");

            /*create the Transformation, put it in the Editor Transformations list*/
            setTransformation(curStartIndex, textLength, replacement, null);
        }


        public void delete(int curStartIndex, int textLength) {
            this.setTransformation(curStartIndex, textLength, "", null);
        }

        /**
         * This method handles the generic request of a Token generation.
         * Therefore it just invokes the setting of a new Transformation object
         * Since we explicitly want to generate a token, the tokenFactory field cannot be null.
         *
         * @param startIndex  first position of the text to edit in the current string
         * @param textLength  length of the text to edit
         * @param replacement string that must substitute the text to edit.
         * @param factory     object that can be employed to create Tokens. It can not be null.
         */
        private void setToken(int startIndex, int textLength, String replacement, TokenFactory factory) {
            if (factory == null)
                throw new IllegalArgumentException("when invoking setToken, the tokenFactory must not be null");

            /*create the Transformation, put it in the Editor Transformations list*/
            setTransformation(startIndex, textLength, replacement, factory);
        }

        /**
         * This method handles the specific request of a WORD Token.
         * It thus requests the setting of a new Token,
         * passing the specific TokenFactory WORD_FACTORY that is used
         * to generate WORD Tokens.
         *
         * @param startIndex  first position of the text to edit in the current string
         * @param length      length of the text to edit
         * @param replacement string that must substitute the text to edit.
         */
        public void setWord(int startIndex, int length, String replacement) {
            /*create the Transformation, put it in the Editor Transformations list;
             * as a TokenFactory use a WORD_FACTORY*/
            this.setToken(startIndex, length, replacement, TokenFactory.WORD_FACTORY);
        }

        /**
         * This method handles the specific request of a TAG Token.
         * It thus requests the setting of a new Token,
         * passing the specific TokenFactory factory that is used
         * to generate TAG Tokens.
         *
         * @param startIndex  first position of the text to edit in the current string
         * @param length      length of the text to edit
         * @param replacement string that must substitute the text to edit.
         * @param factory     the tokenFactory use to generate the TAG token of the right type.
         */
        public void setTag(int startIndex, int length, String replacement, TokenFactory factory) {
            /*create the Transformation, put it in the Editor Transformations list;
             *  use the parameter factory as a TokenFactory */

            this.setToken(startIndex, length, replacement, factory);
        }

        /**
         * This method ends this Editor's scan on the current version of the String.
         * <p>
         * The Editor iterates over all the Transformations it has created
         * during the current scan over the current version of the String.
         * For each Transformation it executes replacements on the currentString, if required;
         * moreover, it updates the Transformation indexes
         * in order to make them refer to the original (immutable) string,
         * instead of the continuously modified currentString.
         * <p>
         * More in detail, for each Transformation
         * replacements lead to alter the currentString, so the next transformation indexes
         * won't refer to the same positions and characters anymore.
         * Therefore, after each replacement the Editor updates an offset field
         * that, added to the not updated start and end of the next transformation,
         * will lead to the start and end on the just updated currentString.
         * <p>
         * Moreover, the Editor also uses and, after each replacement, updates,
         * the SentenceBuilder IndexMap mapping each position on the currentString
         * to the corresponding position on the originalString.
         * <p>
         * Therefore the Editor can easily set the start and end fields of the transformation
         * so that they refer to the right positions on the original String
         * instead of on the current one.
         * <p>
         * The Editor finally sends all its transformations to the SentenceBuilder,
         * and prepares for new scan.
         */
        public SentenceBuilder commit() {

            /*distance between the current String and the original String transformation*/
            int offset = 0;

            /*executes replacements*/
            for (Transformation t : this.localTransformations) {

                /*computes the transformation start on the currentString*/
                int currentStart = t.start + offset;
                /*computes the transformation end on the currentString*/
                int currentEnd = t.end + offset;
                /*computes the transformation start on the originalString*/
                t.start = indexMap.get(currentStart);
                /*computes the transformation end on the originalString*/
                t.end = indexMap.get(currentEnd);

                /*process replacement, if necessary*/
                if (t.replacement != null) {
                    /*replace the symbols on the currentString*/
                    currentString.replace(currentStart, currentEnd, t.replacement);
                    /*update the mapping information in the indexMap:
                     * the portion from currentStart to currentEnd
                     * has become t.replacement.length() long*/
                    indexMap.update(currentStart, currentEnd, t.replacement.length());
                    /*since the replacement was now completed,
                     * the following transformation must now use a different offset*/
                    offset = offset - t.text.length() + t.replacement.length();
                }
            }
            /*add this Editor's transformation list to the SentenceBuilder Transformation lists*/
            transformations.addAll(this.localTransformations);

            /*make the editor ready to start over with a new client*/
            localTransformations.clear();
            this.inUse = false;

            return SentenceBuilder.this;
        }

        /**
         * Since before commit() no replacements are executed
         * and no Transformations are employed,
         * there is no operation to rollback.
         * All that this method must do is prepare the editor
         * for the next client, by clearing the transformation list
         * and by marking the editor as free from clients.
         */
        public void abort() {
            /*make the editor ready to start over*/
            localTransformations.clear();
            this.inUse = false;
        }
    }
}