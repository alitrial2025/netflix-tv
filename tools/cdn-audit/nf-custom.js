$(".model-btn-play").click(function(e){
var type = $("#exampleModalScrollable").attr("data-type");
if(type=="t"){var VideoID = $("#exampleModalScrollable").attr("data-ep");
}else{var VideoID = $("#exampleModalScrollable").attr("data-post");}
playerstart(VideoID);
});
$(".btn-play").click(function(e){
var VideoID = $(this).attr("data-post");
//playerstart(VideoID);
});
function eps_play(VideoID){
playerstart(VideoID);
}
function set_lang(elem,Lang){
$(".audio_lang_list a").removeClass("active");
var thislang = $(elem);

$.ajax({type: "POST", url: "/mobile/language.php", data:{lang:Lang},xhrFields: { withCredentials: true}, success: function(result){
thislang.addClass("active");
},error: function () {alert_msg("danger","Language Set Failed, Internet ERROR");}
});

}
//////////////////////////////////////////////////////////
$(".btn-close").click(function() {
// $(".modal-dialog").animate({"top": "110vh"});
// $("#exampleModalScrollable").toggle(500);
//$("#exampleModalScrollable").css({"background-color": "rgba(0,0,0,0)"});
$(".modal-dialog").animate({
    top: "110vh"
}, 500, function() {
    $("#exampleModalScrollable").hide();
});
clear_model_post();
//$("#exampleModalScrollable").css({"display": "none"});
//$(this).closest("table").find(".hid").toggle(1000);
//$("#moremp3").hide();
//$(this).closest("table").find("#moremp3").hide();
//$("#hidemp3").show();
//$(this).closest("table").find("#hidemp3").show();
});
$(".post-data").click(function() {
var post_id = $(this).attr("data-post");
set_model_post(post_id);
});
$(".btn-mylist").click(function() {
var post_id = $(this).attr("data-post");
MyWatchList(post_id);
});
$(".btn-mylist2").click(function() {
var post_id = $("#exampleModalScrollable").attr("data-post");
MyWatchList(post_id);
});
$(".share-btn").click(function() {
//alert(window.location.pathname);
});
$(".account").click(function() {
$("#loginform").removeClass("hide").animate({"top": "15%"});
$(".dark-bg").removeClass("hide");
});
$(".login-btn-1").click(function() {
$(".login-in-email").addClass("hide");
$(".login-in-otp").removeClass("hide");
$(".login-btn-1").addClass("hide");
$(".login-btn-2").removeClass("hide");
});
$(".dark-bg").click(function() {
$("#loginform").animate({"top": "-85%"});
$("#setting-box").animate({"top": "-85%"});
$(".dark-bg").addClass("hide");
setTimeout(function() {
$("#loginform").addClass("hide");
$("#setting-box").addClass("hide");
}, 500);
});
$(".setting-btn").click(function() {
$("#setting-box").removeClass("hide").animate({"top": "25%"});
$(".dark-bg").removeClass("hide");
});
$('.hd-onoff-input').change(function () {
$.ajax({type: "POST", url: "/mobile/setting.php", data:{hd:"hd"},xhrFields: { withCredentials: true}, success: function(result){}
});});
$(".searchIcon").click(function() {
//$("#search").removeClass("hide");
$(".search").toggle(200);
$(".search").animate({"left": "0px"});
$(".body").addClass("overflow2");
search_input();
$("#search-input").focus();
});
$(".btn-search-close").click(function() {
//$("#search").addClass("hide");
$(".search").animate({"left": "110vw"});
$(".search").toggle(200);
$(".search-result").empty();
$(".body").removeClass("overflow2");
$("#search-input").val(null);
$(".advancesearch-input").prop("checked", false);
});
$(".recent-delete").click(function() {
var post_id = $(this).closest(".recentwatch").find(".post-data").attr("data-post");
$('[data-post="'+post_id+'"][data-delete]').closest(".recentwatch").remove();
$.ajax({type: "POST", url: "/mobile/recentplay.php", data:{deleteid:post_id},xhrFields: { withCredentials: true}, success: function(result){}});
});
$(".ott-act").click(function() {
var ott_id = $(this).attr("data-ott");
var ott_img = $(this).find(".ott-img").attr("src");
$(".move-ott-3 img").attr("src", ott_img);
$(".move-ott").removeClass("hide");
$.ajax({type: "POST", url: "/mobile/setting.php", data:{ott:ott_id},xhrFields: { withCredentials: true}, success: function(result){
if(result.error=="no"){setTimeout(function(){location.reload();}, 1000); }else{$(".move-ott").addClass("hide");alert_msg("warning",result.error);}
}});
});
$(".recent-info").click(function() {
$(this).closest(".recentwatch").find(".post-data").trigger("click");
});
var timeoutId;
$("#search-input, .advancesearch-input").on("input", function(){
$(".search-result").html('<div class="result-error">Loading Please Wait</div>');
window.clearTimeout(timeoutId);
timeoutId = window.setTimeout(function() {search_input();}, 2000);
});
$(".model-md").click(function(e) {
$(".model-morethis div div").removeClass("active");
$(".model-morethis div").removeClass("active-box");
$(this).addClass("active");
$(".model-md-box").addClass("active-box");
});
$(".model-ep").click(function(e) {
$(".model-morethis div div").removeClass("active");
$(".model-morethis div").removeClass("active-box");
$(this).addClass("active");
$(".model-ep-box").addClass("active-box");
});
$(".model-mlt").click(function(e) {
$(".model-morethis div div").removeClass("active");
$(".model-morethis div").removeClass("active-box");
$(this).addClass("active");
$(".model-mlt-box").addClass("active-box");
$(this).attr("data-read");
});
$(".model-btn-play2, .btn-play2").click(function(e) {
$("#player").removeClass("hide");
$(".player-box").append('<iframe src="https://streamtape.net/e/9exx98qM8wIaMo1/" allowfullscreen allowtransparency allow="autoplay" scrolling="no" frameborder="0"></iframe>').trigger('play');
});
$(".appdownload").click(function(e) {
$(".app-download").removeClass("hide");
var os = $(this).attr("data-os");
$(".app-download-show").append('<iframe class="app-download-iframe" src="/mobile/app-info.php?os='+os+'" width="100vw" height="100vh" frameborder="0"></iframe>');
});
$(".app-download-close").click(function(e) {
$(".app-download").addClass("hide");
$(".app-download-show").empty();
});
////////////////////////////////////////////////////////////////////////////////////////////////////////////
$(".ott-apps .ott-actno").click(function(){
var OTTmsg = $(this).attr("data-msg");
if(OTTmsg==""){alert_msg('warning','Only Netflix, PrimeVideo and Disney+, More OTT Coming Soon.');}else{alert_msg('warning',OTTmsg);}
});
$(".model-btn-download").click(function(){alert_msg('danger','Download is Disabled, Activated Soon as Possible.');});
$(".btn-close2").click(function(){$(".btn-close").removeAttr("style");});
$('.season-box').change(function () {
//var id = $(this).find(':selected')[0].value;
$(".model-episodes-list").addClass("ep-load");
var t = $(".body").attr("data-time");
var extraurl = $(".body").attr("data-extra");
var s = $(this).val();
var series = $("#exampleModalScrollable").attr("data-post");
$.ajax({type: "GET", url: extraurl+"/mobile/episodes.php", data:{s:s,series:series,t:t},xhrFields: { withCredentials: true}, success: function(result){
if(result.nextPageShow=="1"){$(".more-episodes").removeClass("hide");$(".more-episodes button").attr("data-nextPage", result.nextPage).attr("data-nextPageSeason", result.nextPageSeason);}else{$(".more-episodes").addClass("hide");$(".more-episodes button").attr("data-nextPage", "").attr("data-nextPageSeason", "");}
ep_data(result.episodes);
audio_lang(result.lang,result.d_lang);
},
error: function () {alert_msg("danger","Episode Loading Failed, Internet ERROR.");}
});
});
$(".btn-play-trailer").click(function(){
//var trailer = $("#exampleModalScrollable").attr("data-post");
//$(".modal-img-poster").addClass("hide");
//$(".play-btn-s").animate({"top": "10px"}).css({"bottom": "unset"});
//$(".btn-play-trailer").addClass("hide");
//$(".modal-img").append('<video class="modal-video-trailer" autoplay controls preload="none" poster="https://image.top/poster/h/'+trailer+'.jpg" style="object-fit: fill;"><source src="/trailer.php?id='+trailer+'" type="video/mp4">Your browser does not support HTML video.</video>').trigger('play');
$('.model-btn-play').trigger( "click" );
});

$(".more-episodes button").click(function() {
var t = $("body").attr("data-time");
var extraurl = $(".body").attr("data-extra");
var s = $(this).attr("data-nextpageseason");
var nextPage = $(this).attr("data-nextpage");
var series = $("#exampleModalScrollable").attr("data-post");
$.ajax({type: "GET", url: extraurl+"/mobile/episodes.php", data:{s:s,series:series,t:t,page:nextPage},xhrFields: { withCredentials: true}, success: function(result){
if(result.nextPageShow=="1"){$(".more-episodes").removeClass("hide");$(".more-episodes button").attr("data-nextPage", result.nextPage).attr("data-nextPageSeason", result.nextPageSeason);}else{$(".more-episodes").addClass("hide");$(".more-episodes button").attr("data-nextPage", "").attr("data-nextPageSeason", "");}
ep_data(result.episodes,"no");
},
error: function () {alert_msg("danger","Episode Loading Failed, Internet ERROR.");}
});
});
$(".telegram-btn").click(function (event) {
event.preventDefault();
var val = $(this).attr("data-url");
CopyToClipboard(val);
$(".telegram-btn h4").text("TeleGram Link Copied").css({"color": "#05c605"});
var telemsg = "TeleGram Link Copied, Now Open Telegram and Paste link in Search Bar and Join Channel.";
$(".telegram-msg").text(telemsg);
alert_msg("success",telemsg)
});
function CopyToClipboard(value) {
var $temp = $("<input>");
$("body").append($temp);
$temp.val(value).select();
document.execCommand("copy");
$temp.remove();
}
function ep_data(object,clear_ep_box="yes"){
if(clear_ep_box=="yes"){$(".model-episodes-list").removeClass("ep-load").empty();}
$.each(object, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
if(jsonObject["complate"]=="0"){var parcent ='';}else{var parcent ='<progress max="100" class="ep-bar" value="'+jsonObject["complate"]+'"></progress>';}
$(".model-episodes-list").append('<div class="ep-solo" data-ep_id="'+jsonObject["id"]+'" data-ep_num="'+jsonObject["s"]+' • '+jsonObject["ep"]+'"><div class="ep-img" onclick="eps_play(\''+jsonObject["id"]+'\')"><div class="ep-img-0"><img class="ep-poster" src="https://imgcdn.kim/epimg/150/'+jsonObject["id"]+'.jpg" alt="'+jsonObject["t"]+'">'+parcent+'</div><div class="ep-play"><img class="ep-play-btn" src="/mobile/img/play.svg"></div></div><div class="ep-words"><div class="ep-title">'+jsonObject["t"]+'</div><span class="ep-duration">'+jsonObject["ep"]+'<b> • </b>'+jsonObject["s"]+'<b> • </b>'+jsonObject["time"]+'</span><p class="ep-des"></p></div></div>');
}
});
}

function clear_model_post(){
//var homeurl = $(".body").attr("data-homeurl");
//var appquary = $(".body").attr("data-appquary");
//window.history.pushState('dp', 'hp', homeurl+appquary);
$(".modal-body").scrollTop(0);
$(".app").removeClass("effect");
setTimeout(function() {
$(".modal-body").scrollTop(0);
$(".play-btn-s").removeAttr("style");   
$(".modal-img-poster").removeClass("hide").attr("src","");
$(".btn-play-trailer").removeClass("hide");
$(".body").removeClass("overflow");
$(".modal-body-d").addClass("hide");
$(".modal-body-c").addClass("hide");
$(".modal-progress").addClass("hide");
$(".modal-progress-title").empty();
$(".modal-video-trailer").remove();
$("#exampleModalScrollable").attr("data-post", "").attr("data-type", "").attr("data-ep", "").removeClass("show");
$(".model-title").empty();
$(".model-year").empty();
$(".model-ua").empty();
$(".audio_lang_list").empty();
$(".model-match").empty();
$(".model-runtime").empty();
$(".model-film-series-img").empty();
$(".btn-main-play").text('Play');
$(".model-Director").empty();
$(".model-Writer").empty();
$(".model-Creator").empty();
$(".model-Cast").empty();
$(".model-Genres").empty();
$(".model-Genres").empty();
$(".model-Thisis").empty();
$(".model-Maturity").empty();
$(".model-Maturity-info").empty();
$(".short_cast").empty();
$(".more-episodes").addClass("hide");
$(".more-episodes .section-expandButton").attr("data-nextPage", "").attr("data-nextPageSeason", "");


$(".model-hdsd").empty();
$(".model-description").empty();
$(".model-mlt").removeClass("active");
$(".model-morethis div").removeClass("active-box");
$(".model-morethis .model-row div").removeClass("active");
$(".model-mlt-box").empty();
$(".model-ep-box .season-box").empty();
$(".model-episodes-list").addClass("ep-load").empty().html('<div class="ep-solo"></div><div class="ep-solo"></div><div class="ep-solo"></div><div class="ep-solo"></div>');
}, 500);
}
function set_model_post(post_id=""){
var appquary = $(".body").attr("data-appquary");
var extraurl = $(".body").attr("data-extra");
//$("#exampleModalScrollable").css({"background-color": "rgba(0,0,0)"});
$(".app").addClass("effect");
//window.history.pushState('dd', 'hi', '/watch/'+post_id+appquary);
var t = $(".body").attr("data-time");
$(".modal-dialog").animate({"top": "0px"});
$("#exampleModalScrollable").attr("data-post",post_id).css({"display": "block"}).addClass("show");
$(".modal-img-poster").attr("src","https://imgcdn.kim/poster/h/"+post_id+".jpg");
setTimeout(function() {$('.btn-play-trailer').focus();}, 1000);  
$(".body").addClass("overflow");
$(".modal-body-c").removeClass("hide");
//$.ajax({type: "GET", url: "/mobile/post.php", data:{id:post_id,t:t}, success: function(result){
$.ajax({type: "GET", url: extraurl+"/mobile/post.php", data:{id:post_id,t:t},xhrFields: { withCredentials: true}, success: function(result){
if(result.status=="n"){location.reload();}
$(".model-title").text(result.title);
$(".model-year").text(result.year);
$(".model-ua").text(result.ua);
$(".model-match").text(result.match);
$(".model-runtime").text(result.runtime);
$(".model-hdsd").text(result.hdsd);
$(".model-description").text(result.desc);
$("#exampleModalScrollable").attr("data-type", result.type);
if(result.resume){
$(".btn-main-play").text('Resume');
$(".modal-head-time").text(result.resume_time);
$(".modal-progress").removeClass("hide");
$(".modal-progress-completed").css({width: result.resume_percent+"%"});
$(".modal-progress-title").text(result.resume_title);

}
if(result.creator){$(".model-Creator").html('<th>Creator:</th><td colspan="2">'+result.creator+'</td>');}
if(result.director){$(".model-Director").html('<th>Director:</th><td colspan="2">'+result.director+'</td>');}
if(result.writer){$(".model-Writer").html('<th>Writer:</th><td colspan="2">'+result.writer+'</td>');}
if(result.cast){$(".model-Cast").html('<th>Cast:</th><td colspan="2">'+result.cast+'</td>');$(".short_cast").text(result.short_cast);}
if(result.genre){$(".model-Genres").html('<th>Genres:</th><td colspan="2">'+result.genre+'</td>');}
if(result.ua){$(".model-Maturity").html('<th>Maturity rating:</th><td style="width: 100px;padding: 0 0 0 10px;"><span>'+result.ua+'</span></td><td>'+result.m_reason+'</td>');}
if(result.m_desc){$(".model-Maturity-info").html('<th>Maturity Info:</th><td colspan="2">'+result.m_desc+'</td>');}
if(result.oin){$(".model-film-series-img").html('<img src="/mobile/img/nf'+result.type+'.png">');}
suggest_data(result.suggest);
audio_lang(result.lang,result.d_lang);
if(result.type=="t"){
$(".model-ep").addClass("active").removeClass("hide");
$("#exampleModalScrollable").attr("data-ep", result.last_ep);
$(".model-ep-box").addClass("active-box");
if(result.thismovieis){$(".model-Thisis").html('<th>This show is:</th><td colspan="2">'+result.thismovieis+'</td>');}
if(result.nextPageShow=="1"){$(".more-episodes").removeClass("hide");$(".more-episodes button").attr("data-nextPage", result.nextPage).attr("data-nextPageSeason", result.nextPageSeason);}else{$(".more-episodes").addClass("hide");$(".more-episodes button").attr("data-nextPage", "").attr("data-nextPageSeason", "");}
ep_data(result.episodes);    
select_season(result.season);
}else{
//suggest_data(result.suggest);
if(result.thismovieis){$(".model-Thisis").html('<th>This movie is:</th><td colspan="2">'+result.thismovieis+'</td>');}
$(".model-mlt").addClass("active");
$(".model-mlt-box").addClass("active-box");
$(".model-ep").addClass("hide");}
$(".modal-body-c").addClass("hide");
$(".modal-body-d").removeClass("hide");
},
error: function () {alert_msg("danger","Loading Failed, Internet ERROR.");}
});
}
function select_season(season){
$.each(season, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
$(".model-ep-box .season-box").append('<option value="'+jsonObject["id"]+'"'+jsonObject["sele"]+'>Season '+jsonObject["s"]+' ('+jsonObject["ep"]+' EP)</option>');
}
});
}
function audio_lang(lang,d_lang){
$(".audio_lang_list").empty();
$.each(lang, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
if(jsonObject["s"]==d_lang){dlang="class=\"active\" ";}else{dlang="";}
$(".audio_lang_list").append('<a '+dlang+'onclick="set_lang(this,\''+jsonObject["s"]+'\')">'+jsonObject["l"]+'</a>');
}
});
}

function search_input(){
setTimeout(function() {
var t = $(".body").attr("data-time");
var extraurl = $(".body").attr("data-extra");
var search_quary = $("#search-input").val();
var ADSearch = $(".advancesearch-input").is(":checked");
$.ajax({type: "GET", url: extraurl+"/mobile/search.php", data:{s:encodeURIComponent(search_quary),t:t,ADSearch,ADSearch},xhrFields: { withCredentials: true}, success: function(result){
$(".search-head").text(result.head)
if(result.type==0){search_data(result.searchResult,result);}
   else if(result.type==1){top_search(result.searchResult,result);}
   else if(result.type==2){ADVsearch_data(result.searchResult,result);}
},
error: function () {alert_msg("danger","Search Result Failed, Internet ERROR.");}
});
}, 700);  
}

function search_data(searchData,result){
$(".search-result").empty();
if(result.error){$(".search-result").html('<div class="result-error">'+result.error+'</div>');}
$.each(searchData, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
$(".search-result").append('<div class="search-p" onclick="set_model_post('+jsonObject["id"]+')"><img src="https://imgcdn.kim/nf/v/200/'+jsonObject["id"]+'.jpg" class="search-img" alt="'+jsonObject["t"]+'"></div>');
}
});  
}

function ADVsearch_data(searchData,result){
$(".search-result").empty();

$(".search-result").append(`<div class="result-nf"><h2>NETFLIX</h2><div class="nf_row"></div></div>`);
if(searchData.nf.error){$(".nf_row").html('<div class="ott-error">'+searchData.nf.error+'</div>');}else{
$.each(searchData.nf.r, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
$(".nf_row").append('<div class="search-p search-all" onclick="open_ott('+jsonObject["id"]+')"><img src="https://imgcdn.kim/nf/v/200/'+jsonObject["id"]+'.jpg" class="search-img" alt="'+jsonObject["t"]+'"></div>');
}
});
}

$(".search-result").append(`<div class="result-pv"><h2>PrimeVideo</h2><div class="pv_row"></div></div>`);
if(searchData.pv.error){$(".pv_row").html('<div class="ott-error">'+searchData.pv.error+'</div>');}else{
$.each(searchData.pv.r, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
$(".pv_row").append('<div class="search-p-hori" onclick="open_ott(\''+jsonObject["id"]+'\')"><img src="https://imgcdn.kim/pv/341/'+jsonObject["id"]+'.jpg" class="search-img" alt="'+jsonObject["t"]+'"></div>');
}
});
}

$(".search-result").append(`<div class="result-hs"><h2>Disney+ HotStar</h2><div class="hs_row"></div></div>`);
if(searchData.hs.error){$(".hs_row").html('<div class="ott-error">'+searchData.hs.error+'</div>');}else{
$.each(searchData.hs.r, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
$(".hs_row").append('<div class="search-p search-all" onclick="open_ott('+jsonObject["id"]+')"><img src="https://imgcdn.kim/hs/v/166/'+jsonObject["id"]+'.jpg" class="search-img" alt="'+jsonObject["t"]+'"></div>');
}
});
}
}

function suggest_data(suggestData){
$(".model-mlt-box").empty();
$.each(suggestData, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
$(".model-mlt-box").append('<div class="suggest" onclick="set_model_post_related('+jsonObject["id"]+')"><img src="https://imgcdn.kim/nf/v/200/'+jsonObject["id"]+'.jpg" class="suggest-img" alt="'+jsonObject["id"]+'"></div>');
}
});  
}
function open_ott(msg){
alert_msg("warning","Go to OTT section and Watch there.<br>Here you can only see on which OTT platform it is uploaded.");    
}
function set_model_post_related(vid){
// $(".modal-dialog").animate({"top": "110vh"});
// $("#exampleModalScrollable").toggle(500);
// clear_model_post();
$(".btn-close2").click();
setTimeout(function() {
set_model_post(vid);
}, 900);  
}
function top_search(searchData){
$(".search-result").empty();
$.each(searchData, function(index, jsonObject){     
if(Object.keys(jsonObject).length > 0){
$(".search-result").append('<div class="top-search-solo" onclick="set_model_post('+jsonObject["id"]+')"><div class="top-search-img"><img class="top-search-poster" src="https://imgcdn.kim/poster/341/'+jsonObject["id"]+'.jpg" alt="'+jsonObject["t"]+'"></div><div class="top-search-title">'+jsonObject["t"]+'</div><div class="top-search-play"><img class="top-search-play-btn" src="/mobile/img/play.svg"></div></div>');
}
});  
}
function audio_menu(){
setTimeout(function(){ $('.jw-icon-settings').trigger( "click" );$('.jw-submenu-audioTracks').trigger( "click" );}, 100);
}
function zoom_func(){
if($("#jw").hasClass("jw-stretch-none")){
jwplayer('jw').setConfig({"stretching": "uniform"});
$(".jw-aspect").text("Best Fit").addClass("jw-aspect2");
}else if($("#jw").hasClass("jw-stretch-uniform")){

jwplayer('jw').setConfig({"stretching": "fill"});
$(".jw-aspect").text("Fit Screen").addClass("jw-aspect2");
}else if($("#jw").hasClass("jw-stretch-fill")){


    
jwplayer('jw').setConfig({"stretching": "exactfit"});
$(".jw-aspect").text("Fill").addClass("jw-aspect2");

}else if($("#jw").hasClass("jw-stretch-exactfit")){

jwplayer('jw').setConfig({"stretching": "none"});
$(".jw-aspect").text("None").addClass("jw-aspect2");
}
setTimeout(function() {$(".jw-aspect").removeClass('jw-aspect2');}, 1700);

}
async function myrotate(newOrientation="portrait") {
if (!document.fullscreenElement && $(window).width() < 900) {await document.documentElement.requestFullscreen();}
if ($(window).width() < 900) {await screen.orientation.lock(newOrientation);}


}



function createCookie(name, value, days) {
    var expires;

    if (days) {
        var date = new Date();
        date.setTime(date.getTime() + (days * 24 * 60 * 60 * 1000));
        expires = "; expires=" + date.toGMTString();
    } else {
        expires = "";
    }
    document.cookie = encodeURIComponent(name) + "=" + encodeURIComponent(value) + expires + ";domain="+window.location.hostname +"; path=/";
}
function readCookie(name) {
    var nameEQ = encodeURIComponent(name) + "=";
    var ca = document.cookie.split(';');
    for (var i = 0; i < ca.length; i++) {
        var c = ca[i];
        while (c.charAt(0) === ' ')
            c = c.substring(1, c.length);
        if (c.indexOf(nameEQ) === 0)
            return decodeURIComponent(c.substring(nameEQ.length, c.length));
    }
    return null;
}
function MyWatchList(VideoID) {
$.ajax({type: "POST", url: "/mobile/MyWatchList.php", data:{id:VideoID},xhrFields: { withCredentials: true}, success: function(result){
alert_msg(result.col,result.msg);
},
error: function () {alert_msg("danger","Request Fail: Internet ERROR");}
});
}
function Re_newdata(){
var t_hash = $("body").attr("data-hash");
$.ajax({type: "POST", url: "/mobile/p.php", data:{hash:t_hash},xhrFields: { withCredentials: true}, success: function(result){
if(result.r=="y"){location.reload();}
}
});
}
function alert_msg(color,msg,secs="5000"){
var msgsvg="";
if(color=="success"){var msgsvg ='<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg> ';}
if(color=="warning"){var msgsvg ='<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>';}
if(color=="danger"){var msgsvg ='<svg xmlns="http://www.w3.org/2000/svg" class="w-6 h-6 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"></path></svg>';}
if(msg==""){var msgsvg = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" style="margin: auto;" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid"><circle cx="50" cy="50" fill="none" stroke="#e15b64" stroke-width="10" r="35" stroke-dasharray="164.93361431346415 56.97787143782138"><animateTransform attributeName="transform" type="rotate" repeatCount="indefinite" dur="1s" values="0 50 50;360 50 50" keyTimes="0;1"></animateTransform></circle></svg>';}
$(".pop-alert").html(msgsvg+' '+msg).addClass(color).addClass("show");
setTimeout(function(){$(".pop-alert").text("").removeClass(color).removeClass("show"); }, secs);
}
function playerstart(VideoID) {
var t = $(".body").attr("data-time");
var gettitle = $(".model-title").text();
$("#player").removeClass("hide");
$(".body").addClass("overflow3");
myrotate("landscape");
const playerInstance = jwplayer('jw').setup({
playlist: "/mobile/playlist.php?id="+VideoID+"&t="+encodeURIComponent(gettitle)+"&tm="+t,
width: "100%",
height: "100%",
preload: "auto",
label: "360p",
default: true,
hlshtml: true,
//autostart: false,
autostart: "viewable",
primary: 'html5',
//type: "application/vnd.apple.mpegurl",
type:"mp4",
androidhls: true,
horizontalVolumeSlider: true,
pipIcon:"disabled",
playbackRateControls: true,
renderCaptionsNatively: false,
//defaultBandwidthEstimate: 4000,
//"stretching": "uniform",
responsive: true,
"key": "c6Wt+3BVVfc9af5UKM76pP5Vxu+Tu55CBvqMZGg3pEc=",
"skin": {
"controlbar": {
"background": "rgba(245, 245, 245, 0)",
"icons": "#ffffff",
"iconsActive": "rgb(244, 129, 129)"
},
"timeslider": {
"progress": "rgba(253, 0, 0)",
"rail": "rgba(247, 244, 244, 0.40)",
"buffer": "rgb(255, 255, 255)"
},
"tooltips": {
//"background": "rgba(0, 0, 0)",
//"text": "#FFFFFF"
}},
});

playerInstance.on('play', function(){
var type = $("#exampleModalScrollable").attr("data-type");
if(type=="t"){
var SeriesID = $("#exampleModalScrollable").attr("data-post");
createCookie("SE"+SeriesID, VideoID,60);
}
});

playerInstance.once('play', function(){
var type = $("#exampleModalScrollable").attr("data-type");
if(type=="t"){
var SeriesID = $("#exampleModalScrollable").attr("data-post");
var RecentPlay = "SE"+SeriesID;}else{var RecentPlay = VideoID;}
$.ajax({type: "POST", url: "/mobile/recentplay.php", data:{recentplay:RecentPlay},xhrFields: { withCredentials: true}, success: function(result){}
});

if(type=="t"){
var nextep = $('[data-ep_id="'+VideoID+'"]').next('[data-ep_id]').attr("data-ep_id");
if(nextep.length > 0){
$(".jw-nextup-thumbnail").before('<button type="button"  onclick="eps_play(\''+nextep+'\')" class="player-next-btn"><img src="/mobile/img/playdark2222.svg" style="margin-right: 10px;"> <span class=small-pnb>Next EP</span><span class=big-pnb>Next Episode</span></button>');
$('.jw-nextup-container').css({"visibility": "visible"});
}
}
});

playerInstance.on('buffer', function(){
//if($("#player").find("player-msg")){}else{$(".jw-title-primary").before('<span class="player-msg">You\'re Watching</span>');}
});
playerInstance.on('pause', function(){
//if($("#player").find("player-msg")){}else{$(".jw-title-primary").before('<span class="player-msg">You\'re Watching</span>');}
//
//setTimeout(function(){ $('.jw-icon-settings').trigger( "click" );}, 100);
//$(".jw-settings-close").click(function() {setTimeout(function(){ $('.jw-icon-settings').trigger( "click" );jwplayer().play();}, 1000);});
//jw-settings-close
//setTimeout(function() {$('.jw-settings-close').focus();}, 1000);
});

playerInstance.on('error', function(){
$(".jw-error-text.jw-reset-text").html('Video Under Progress.<span class="jw-break jw-reset"></span><span class="jw-error-text-2">Try Again After Some Time.</span>');
});

playerInstance.on('setupError', function(){
$(".jw-error-text.jw-reset-text").html('Internet ERROR.<span class="jw-break jw-reset"></span><span class="jw-error-text-2"><button onclick="eps_play(\''+VideoID+'\')" style="padding: 10px 20px;background: white;color: black;font-size: 18px;border-radius: 7px;display: flex;align-items: center;font-weight: bolder;"><img class="svg-icon" src="/mobile/img/playdark2222.svg" style="margin-right: 7px;"> Replay<div></div></button>Click the Replay button to try again.</span>');
});

playerInstance.on('ready', function(){
var getYear = $(".model-year").text();
var getUA = $(".model-ua").text();
var getTime = $(".model-runtime").text();
var getDesc = $(".model-description").text();
var centerTitle = $(".model-title").text();
$(".jw-title-primary").before("<span class='player-msg'>You\'re Watching</span>");
$(".jw-title-secondary").append("<span>"+getYear+"</span> <span>"+getUA+"</span> <span>"+getTime+"</span>");

if($("#exampleModalScrollable").attr("data-type")=="t"){
var EpTitle = $('[data-ep_id="'+VideoID+'"]').find(".ep-title").text();
var Epnum = $('[data-ep_id="'+VideoID+'"]').attr("data-ep_num");
//var EpDesc = $('[data-ep_id="'+VideoID+'"]').find(".previewModal--small-text").text();
$(".jw-title-secondary").after("<div class=\"jw-title-secondary player-ep-info\"><span>"+Epnum+":</span> <span>"+EpTitle+"</span></div>");
$(".jw-reset.jw-spacer").html('<span class="player-bottom-title"><b>'+Epnum+'</b> '+centerTitle+' – <span>'+EpTitle+'</span></span>');
//if(EpDesc){var getDesc=EpDesc;}
}else{$(".jw-reset.jw-spacer").html('<span class="player-bottom-title">'+centerTitle+'</span>');}

$(".jw-title.jw-reset-text").append("<p class='jw-title-des'>"+getDesc+"</p>");
$(".jw-display-icon-next").replaceWith('<div class="jw-display-icon-container jw-display-icon-forward jw-reset"><div class="jw-icon jw-icon-forward jw-button-color jw-reset" role="button" tabindex="0" aria-label="forward"><img src="/mobile/img/forward22.svg?v7"></div></div>');
$(".jw-display-icon-rewind .jw-icon-rewind").html('<img src="/mobile/img/rewind2222.svg">');
$(".jw-icon-forward").click(function() {playerInstance.seek(playerInstance.getPosition() + 10);});
autoRotate();

$(".jw-controls").append('<div class="btn-payer-back"></div>');

$(".btn-payer-back").click(function(e) {
$("#player").addClass("hide");
$(".body").removeClass("overflow3");
//$(".player-box #jw").empty();
myrotate();
rotateToPortrait()
//exitFullscreen();
//screen.orientation.unlock()
jwplayer().stop().remove();
});
});


playerInstance.once('play', function() {
    
  let cookieData = readCookie(VideoID);
  if (cookieData) {
    //return logger.log('No video resume cookie detected. Refresh page.');

  var resumeAt = cookieData.toString().split(':');
  if (resumeAt[1] != resumeAt[0]) {
	 //alert(resumeAt[0]);
    playerInstance.seek(resumeAt[0]); 
	  
    //logger.log('Resuming at ' + resumeAt);
    //return;
  }
 }
  //logger.log('Video ended last time! Will skip resume behavior');
});

playerInstance.on('time', function(e) {
var dur2 = playerInstance.getDuration();
var dur = dur2.toString().split('.');
var pos = e.position.toString().split('.');
//alert(pos[0]);
if(pos[0] > 20){createCookie(VideoID, `${Math.floor(e.position)}:${dur[0]}`,60);}
if(window.innerHeight > window.innerWidth){
$("#player #jw").addClass("jw-orientation-portrait");
}else{$("#player #jw").removeClass("jw-orientation-portrait");}
});

playerInstance.addButton('/mobile/img/zoom222222.svg','Zoom', 
   function() {
zoom_func();
 },'Zoom'
);

//playerInstance.addButton('/img/rotate2.svg','Rotate Screen', 
//   function() {
//if($("#player").hasClass("rotate")){$("#player").removeClass("rotate");}else{$("#player").addClass("rotate");}
// },'Rotate Screen'
//);

playerInstance.addButton('/mobile/img/audiomenu22.svg','Audio Menu', 
   function() {if ($("#jw-jw-settings-menu").attr("aria-expanded")=="true"){}else{audio_menu();}},'Audio Menu');
}
setInterval(trackamung, 120000);
setInterval(Re_newdata, 180000);
function trackamung(){$("#trackamung img").attr("src","//whos.amung.us/widget/iyify2024mo.png?"+Math.random());}