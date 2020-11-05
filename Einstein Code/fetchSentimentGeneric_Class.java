global class fetchSentimentGeneric_Class {
	//these vars power a visualforce page - fetchSentimentGeneric.vfp
    public static String inputText1{get;set;}
    public static String outputText1{get;set;}

    //this fxn powers a visualforce page - fetchSentimentGeneric.vfp
    public static void request(){
        String allOutput = '';

        EinsteinAPI api = new EinsteinAPI();

        SentimentAnalysisResponse resp = api.findSentiment( inputText1 );

       	Map<String,Double> mappedOutput = api.mapResponse(resp);

        for(String key : mappedOutput.keySet()){
            allOutput += (key + ':' + mappedOutput.get(key) + '|');
        }
        outputText1 = allOutput;
        System.debug(resp);
    }




    /*
    sentimentRequest() is the same fxn as 'request()', except it's prepared to take a unique String argument to be analyzed so
    that it can be used across objects,

	this was working, which is why it's here.
    */


    //BEGIN version 2 - @future

    @InvocableMethod
    public static void sentimentRequest(List<objectAndText> input){

        //List<SentimentOutput> sentiments = new List<SentimentOutput>();
        for(objectAndText a: input){
            makeCallout(a.text, a.objectType, a.objectId, a.previousMessages);
        }

    }

    //redefined higher-level invocation so that other apex classes can use the sentimentRequest function, and it's unique
    //invocable variable object type

    /*
     * Removed by Shawn Soneja, 8/11/20. Not needed for implementation
     *
    public static void sentimentRequestApex(String textToAnalyze, String objectName, String idToUpdate){

            makeCallout(textToAnalyze, objectName, idToUpdate);

    }
	*/

    //previousMessages is a new parameter that determines how many of the most recent messages
    //on a MessagingSession object should be analyzed for Sentiment, in the aggregation.
    @future(callout='true')
    public static void makeCallout(String texts, String objectType, Id objectId, Integer previousMessages){

        //generic
        sObject g;

        System.debug('Record Id: ' + objectId + ' | previousMessage: ' + previousMessages);

        //MessagingSession is handled differently from all other objects because
        //it grabs a number of 'previousMessages' to analyze for Sentiment
        if( objectType == 'MessagingSession'){
            //grabbing message, from most recent to oldest
            List<ConversationEntry> messages = [SELECT Id, Message, ConversationId, EntryTime, ActorType FROM ConversationEntry WHERE ConversationId = :objectId ORDER BY EntryTime DESC];

            /*
             *
             * Removed by Shawn Soneja 8/11, to match new requirements for MessagingSession object
            String joinedMessageTranscript = '';
            //counter to assure we grab only the 50 most recent records
            Integer counter = 0;
            for(ConversationEntry message : messages){
                System.debug('Conversation Entry: Entry Time = ' + message.EntryTime);
                if(message.ActorType == 'EndUser' && counter < 50) joinedMessageTranscript += ' ' + message.Message;
                if(counter >= 50) break;
                counter++;
            }
            joinedMessageTranscript = joinedMessageTranscript.replace(':',' ');
            joinedMessageTranscript = joinedMessageTranscript.replace('\n',' ');
            texts = joinedMessageTranscript;

            SentimentOutput originalSentiment = calloutMethod(texts);
            */

            //custom code to handle query for Messaging Session
            List<SentimentOutput> sentiments = new List<SentimentOutput>();

            //number of recent messages to grab may change
            //Integer prevMessages = Integer.ValueOf(previousMessages);
            if(previousMessages > 20) previousMessages = 20;
            if(previousMessages < 0) previousMessages = 0;

            //array for positive, negative, and neutral sentiment used to find averages
            Double[] averages = new Double[]{0,0,0};
            for(Integer i=0; i<previousMessages; i++){
                SentimentOutput sentiment = calloutMethod(messages[i].Message);
                System.debug('(Positive: '+sentiment.positiveProbability+' ) '+'(Negative: '+sentiment.negativeProbability+' ) '+
                            'Neutral: '+sentiment.neutralProbability+' )');
                averages[0] += sentiment.positiveProbability;
                averages[1] += sentiment.negativeProbability;
                averages[2] += sentiment.neutralProbability;
            }

            //converts the sums to averages
            for(Integer a=0;a<averages.size();a++){
                //number of recent messages to grab may change
                averages[a] = averages[a] / 5;
            }

            System.debug('Granular Averages: ' + '(Positive: '+averages[0]+' ) '+'(Negative: '+averages[1]+' ) '+
                            'Neutral: '+averages[2]+' )');
            //System.debug('Original Average: ' + '(Positive: '+originalSentiment.positiveProbability+' ) '+'(Negative: '+originalSentiment.negativeProbability+' ) '+
                            //'Neutral: '+originalSentiment.neutralProbability+' )');

            //update the object with sentiment values
			MessagingSession c = new MessagingSession();
            c.id = objectId;

            c.Positive_Sentiment__c = averages[0];
            c.Negative_Sentiment__c = averages[1];
            c.Neutral_Sentiment__c = averages[2];
            g = c;
            updateDML(g);
        }else{
            //correction for Email Message TextBody, containing characters that
        	//affect the requesting JSON to einstein
            //prep work to the body of text to be analyzed
            if( objectType == 'EmailMessage'){
                String newText = texts;
                newText = newText.replace(':',' ');
                newText = newText.replace('\n',' ');
                newText = newText.stripHtmlTags();
                texts = newText;
                System.debug('New Text: ' + newText);
                System.debug('Inputted Text: ' + texts);
            }else if( objectType == 'ChatTranscript' ){
                texts = texts.stripHtmlTags();
            }



            //this else block is for if the queried object is not a Messaging Session
            SentimentOutput sentiment = calloutMethod(texts);

            //Find the Object type
            if( objectType == 'Case' ){
                Case c = new Case();
                c.id = objectId;
                c.Sentiment__c = String.valueOf(sentiment.positiveProbability);
                g = c;
            }else if( objectType == 'ChatTranscript'){
                LiveChatTranscript c = new LiveChatTranscript();
                c.id = objectId;
                c.Positive_Sentiment__c = sentiment.positiveProbability;
                c.Negative_Sentiment__c = sentiment.negativeProbability;
                c.Neutral_Sentiment__c = sentiment.neutralProbability;
                g = c;
            }else if( objectType == 'EmailMessage'){
                EmailMessage c = new EmailMessage();
                c.id = objectId;

                c.Positive_Sentiment__c = sentiment.positiveProbability;
                c.Negative_Sentiment__c = sentiment.negativeProbability;
                c.Neutral_Sentiment__c = sentiment.neutralProbability;
                g = c;
            }

            updateDML(g);
        }//end if not a Messaging Session



    }//end makeCallout function

    public static SentimentOutput calloutMethod(String texts){
        //only handling one text right now for testings
        String a = texts;

        //create einstein instance. Houses token info and other relevant methods
        EinsteinAPI api = new EinsteinAPI();


        //findSentiment method grabs token information, makes a request, and returns the sentiment
        SentimentAnalysisResponse resp = api.findSentiment( a );
        //String resp = api.findSentimentString(a);

        //print response
        System.debug('Response: ' + resp);

        //maps outputted json
        Map<String,Double> output = api.mapResponse(resp);

        //String mostProbable = api.mostProbableSentiment(resp);
        List<String> f = new List<String>();


        //List of sentiment strings that should be accessed in the flow
        SentimentOutput q = new SentimentOutput();

        //q.apiOutput = new List<String>();

        List<SentimentOutput> sentiments = new List<SentimentOutput>();

        String outputString;
        for(String key : output.keySet()){
            String g = (key + ' ' + output.get(key));
            outputString += g;
        }
        f.add(outputString);

        Double positive = output.get('positive');
        Double neutral = output.get('neutral');
        Double negative = output.get('negative');
        SentimentOutput so = new SentimentOutput();
        so.positiveProbability = positive;
        so.negativeProbability = negative;
        so.neutralProbability = neutral;
        sentiments.add(so);

        return so;
    }

    //END version 2 -- @future

    //overloading the update
    public static void updateDML(Case c){
        update c;
    }
    public static void updateDML(sObject c){
        update c;
    }
    public static void updateDML(LiveChatTranscript c){
        update c;
    }
    public static void updateDML(EmailMessage c){
        update c;
    }

    global class objectAndText{
        @InvocableVariable
        public String objectType;

        @InvocableVariable
        public String text;

        @InvocableVariable
        public Id objectId;

        @InvocableVariable
        public Integer previousMessages;
    }

    global class SentimentOutput{
        @InvocableVariable
        public Double neutralProbability;

        @InvocableVariable
        public Double positiveProbability;

        @InvocableVariable
        public Double negativeProbability;
    }
}
