package com.gpo.yoin.shared.data.lyrics

/*
 * Live provider responses recorded 2026-10-04/05 (search lists trimmed to the fields the providers read, lyric
 * bodies cut to their opening lines — no full song texts). Ported verbatim from the Android app's
 * src/test/resources/lyrics/{qq,huawei,netease}.
 */
internal object QqFixtures {
    val gatewayRefused2001Json: String = """{"code": 0, "ts": 1791133543239, "req_1": {"code": 2001}}"""

    val gatewaySearchQingtianJson: String = """{
 "code": 0,
 "req_1": {
  "code": 0,
  "data": {
   "code": 0,
   "body": {
    "song": {
     "list": [
      {
       "id": 97773,
       "mid": "0039MnYb0qxYhV",
       "name": "晴天",
       "title": "晴天",
       "interval": 269,
       "singer": [
        {
         "id": 4558,
         "mid": "0025NhlN2yWrP4",
         "name": "周杰伦"
        }
       ],
       "album": {
        "id": 8220,
        "mid": "000MkMni19ClKG",
        "name": "叶惠美"
       }
      },
      {
       "id": 244115385,
       "mid": "004Fs2FP1EvZYc",
       "name": "晴天",
       "title": "晴天 (Live)",
       "interval": 249,
       "singer": [
        {
         "id": 4558,
         "mid": "0025NhlN2yWrP4",
         "name": "周杰伦"
        }
       ],
       "album": {
        "id": 9040723,
        "mid": "004fXSyj3bWTMN",
        "name": "周杰伦地表最强世界巡回演唱会"
       }
      },
      {
       "id": 4962974,
       "mid": "001398Rf3gykz8",
       "name": "晴天",
       "title": "晴天 (Live)",
       "interval": 299,
       "singer": [
        {
         "id": 4558,
         "mid": "0025NhlN2yWrP4",
         "name": "周杰伦"
        }
       ],
       "album": {
        "id": 14323,
        "mid": "0032ezFm3F53yO",
        "name": "周杰伦 2004 无与伦比 演唱会 Live CD"
       }
      }
     ]
    }
   }
  }
 }
}"""

    val legacyLyricQingtian0039MnYb0qxYhVJson: String = """{"retcode":0,"code":0,"subcode":0,"lyric":"[ti:晴天]\n[ar:周杰伦]\n[al:叶惠美]\n[by:]\n[offset:0]\n[00:00.00]晴天 - 周杰伦 (Jay Chou)\n[00:02.25]词：周杰伦\n[00:04.50]曲：周杰伦\n[00:06.75]编曲：周杰伦\n[00:09.00]制作人：周杰伦\n[00:11.25]合声：周杰伦\n[00:13.50]合声编写：周杰伦\n[00:15.75]吉他：蔡科俊Again\n[00:18.00]贝斯：陈任佑\n[00:20.25]鼓：陈柏州\n[00:22.51]录音助理：刘勇志\n[00:24.76]录音工程：杨瑞代（Alfa Studio）\n[00:27.01]混音工程：杨大纬（杨大纬录音工作室）\n[00:29.26]故事的小黄花\n[00:32.71]从出生那年就飘着","trans":""}"""

    val legacyLyricQingtianJsonpBase64Txt: String = """MusicJsonCallback({"retcode":0,"code":0,"subcode":0,"lyric":"W3RpOuaZtOWkqV0KW2FyOuWRqOadsOS8pl0KW2FsOuWPtuaDoOe+jl0KW2J5Ol0KW29mZnNldDowXQpbMDA6MDAuMDBd5pm05aSpIC0g5ZGo5p2w5LymIChKYXkgQ2hvdSkKWzAwOjAyLjI1Xeivje+8muWRqOadsOS8pgpbMDA6MDQuNTBd5puy77ya5ZGo5p2w5LymClswMDowNi43NV3nvJbmm7LvvJrlkajmnbDkvKYKWzAwOjA5LjAwXeWItuS9nOS6uu+8muWRqOadsOS8pgpbMDA6MTEuMjVd5ZCI5aOw77ya5ZGo5p2w5LymClswMDoxMy41MF3lkIjlo7DnvJblhpnvvJrlkajmnbDkvKYKWzAwOjE1Ljc1XeWQieS7lu+8muiUoeenkeS/ikFnYWluClswMDoxOC4wMF3otJ3mlq/vvJrpmYjku7vkvZEKWzAwOjIwLjI1Xem8k++8mumZiOafj+W3ngpbMDA6MjIuNTFd5b2V6Z+z5Yqp55CG77ya5YiY5YuH5b+XClswMDoyNC43Nl3lvZXpn7Plt6XnqIvvvJrmnajnkZ7ku6PvvIhBbGZhIFN0dWRpb++8iQpbMDA6MjcuMDFd5re36Z+z5bel56iL77ya5p2o5aSn57qs77yI5p2o5aSn57qs5b2V6Z+z5bel5L2c5a6k77yJClswMDoyOS4yNl3mlYXkuovnmoTlsI/pu4ToirEKWzAwOjMyLjcxXeS7juWHuueUn+mCo+W5tOWwsemjmOedgA==","trans":""})"""

    val legacyLyricRefused1101Json: String = """{"retcode":1101,"code":1101,"subcode":1101}"""

    val playLyricLemon213086592Json: String = """{"code":0,"ts":1791135068492,"start_ts":1791135068460,"traceid":"055ed508e94d8ef8","req_1":{"code":0,"data":{"songID":213086592,"songName":"","songType":-1,"singerName":"","qrc":0,"crypt":0,"lyric":"W3RpOkxlbW9uICjjgIpVbm5hdHVyYWzjgIvml6XliafkuLvpopjmm7IpXQpbYXI657Gz5rSl546E5birICjjgojjga3jgaUg44GR44KT44GXKV0KW2FsOkxlbW9uICjmn6DmqqwpXQpbYnk6XQpbb2Zmc2V0OjBdClswMDowMC4wMF1MZW1vbiAtIOexs+a0peeOhOW4qyAo44KI44Gt44GlIOOBkeOCk+OBlykKWzAwOjAwLjUzXeivje+8muexs+a0peeOhOW4qwpbMDA6MDEuMDZd5puy77ya57Gz5rSl546E5birClswMDowMS41NF3lpKLjgarjgonjgbAKWzAwOjAyLjg4XeOBqeOCjOOBu+OBqeOCiOOBi+OBo+OBn+OBp+OBl+OCh+OBhg==","trans":"W3RpOkxlbW9uICjjgIpVbm5hdHVyYWzjgIvml6XliafkuLvpopjmm7IpXQpbYXI657Gz5rSl546E5birICjjgojjga3jgaUg44GR44KT44GXKV0KW2FsOkxlbW9uICjmn6DmqqwpXQpbYnk6XQpbb2Zmc2V0OjBdCltrYW5hOjHjgojjga0x44GlMeOBkeOCkzHjgZcx44GXMeOCiOOBrTHjgaUx44GR44KTMeOBlzHjgY3jgofjgY8x44KI44GtMeOBpTHjgZHjgpMx44GXMeOChuOCgV0KWzAwOjAwLjAwXS8vClswMDowMC41M10vLwpbMDA6MDEuMDZdLy8KWzAwOjAxLjU0XeWmguaenOWPquaYr+S4gOWcuuaipgpbMDA6MDIuODhd6YKj6K+l5pyJ5aSa5aW9","roma":"","lrc_t":1786972455,"qrc_t":0,"trans_t":1684980564,"roma_t":0,"lyric_style":0,"classical":0,"introduceTitle":"","introduceText":[{"title":"566A5LuL","content":""},{"title":"6IOM5pmv","content":""}],"vecSongID":null,"track":{"id":0,"type":0,"mid":"","name":"","title":"","subtitle":"","singer":null,"album":{"id":0,"mid":"","name":"","title":"","subtitle":"","time_public":"","pmid":""},"mv":{"id":0,"vid":"","name":"","title":"","vt":0},"interval":0,"isonly":0,"language":0,"genre":0,"index_cd":0,"index_album":0,"time_public":"","status":0,"fnote":0,"file":{"media_mid":"","size_24aac":0,"size_48aac":0,"size_96aac":0,"size_192ogg":0,"size_192aac":0,"size_128mp3":0,"size_320mp3":0,"size_ape":0,"size_flac":0,"size_dts":0,"size_try":0,"try_begin":0,"try_end":0,"url":"","size_hires":0,"hires_sample":0,"hires_bitdepth":0,"b_30s":0,"e_30s":0,"size_96ogg":0,"size_360ra":null,"size_dolby":0,"size_new":null},"pay":{"pay_month":0,"price_track":0,"price_album":0,"pay_play":0,"pay_down":0,"pay_status":0,"time_free":0},"action":{"switch":0,"msgid":0,"alert":0,"icons":0,"msgshare":0,"msgfav":0,"msgdown":0,"msgpay":0,"switch2":0,"icon2":0},"ksong":{"id":0,"mid":""},"volume":{"gain":0,"peak":0,"lra":0},"label":"","url":"","bpm":0,"version":0,"trace":"","data_type":0,"modify_stamp":0,"pingpong":"","aid":0,"ppurl":"","tid":0,"ov":0,"sa":0,"es":"","vs":null,"vi":null,"ktag":"","vf":null,"va":null},"startTs":1547,"transSource":0,"hasContributor":false,"hasTransContributor":false,"singingAnnotationsLyric":"","singingAnnotationsTs":0,"hasMultiTrans":true,"lt_lyric":"","lt_lyric_t":0}}}"""

    val searchCpQingtianJson: String = """{
 "code": 0,
 "data": {
  "keyword": "晴天 周杰伦",
  "song": {
   "curnum": 3,
   "curpage": 1,
   "totalnum": 2763,
   "list": [
    {
     "id": 97773,
     "mid": "0039MnYb0qxYhV",
     "name": "晴天",
     "title": "晴天",
     "interval": 269,
     "singer": [
      {
       "id": 4558,
       "mid": "0025NhlN2yWrP4",
       "name": "周杰伦"
      }
     ],
     "album": {
      "id": 8220,
      "mid": "000MkMni19ClKG",
      "name": "叶惠美"
     }
    },
    {
     "id": 244115385,
     "mid": "004Fs2FP1EvZYc",
     "name": "晴天",
     "title": "晴天 (Live)",
     "interval": 249,
     "singer": [
      {
       "id": 4558,
       "mid": "0025NhlN2yWrP4",
       "name": "周杰伦"
      }
     ],
     "album": {
      "id": 9040723,
      "mid": "004fXSyj3bWTMN",
      "name": "周杰伦地表最强世界巡回演唱会"
     }
    },
    {
     "id": 4962974,
     "mid": "001398Rf3gykz8",
     "name": "晴天",
     "title": "晴天 (Live)",
     "interval": 299,
     "singer": [
      {
       "id": 4558,
       "mid": "0025NhlN2yWrP4",
       "name": "周杰伦"
      }
     ],
     "album": {
      "id": 14323,
      "mid": "0032ezFm3F53yO",
      "name": "周杰伦 2004 无与伦比 演唱会 Live CD"
     }
    }
   ]
  }
 }
}"""

    val searchCpStupidLoveKatakanaJson: String = """{
 "code": 0,
 "data": {
  "keyword": "Stupid Love レディー・ガガ",
  "song": {
   "curnum": 10,
   "curpage": 1,
   "totalnum": 1013,
   "list": [
    {
     "id": 5017814,
     "mid": "000TmRqU1abtzo",
     "name": "Stupid Love",
     "title": "Stupid Love",
     "interval": 214,
     "singer": [
      {
       "id": 19921,
       "mid": "004TfAHg2stYrs",
       "name": "Jason Derulo"
      }
     ],
     "album": {
      "id": 437864,
      "mid": "001Hef5Y4FsyiQ",
      "name": "Tattoos (Explicit)"
     }
    },
    {
     "id": 449497875,
     "mid": "000qylrA24KBrd",
     "name": "stupid love",
     "title": "stupid love",
     "interval": 208,
     "singer": [
      {
       "id": 2812477,
       "mid": "003vRdtj2JdfDd",
       "name": "米卡"
      }
     ],
     "album": {
      "id": 43787300,
      "mid": "004Y8pOq2aYK1N",
      "name": "bleached漂"
     }
    },
    {
     "id": 267472054,
     "mid": "002S2yMK2AC6Go",
     "name": "Stupid Love",
     "title": "Stupid Love",
     "interval": 193,
     "singer": [
      {
       "id": 13769,
       "mid": "001D3C3T2wxP74",
       "name": "Lady Gaga"
      }
     ],
     "album": {
      "id": 10751601,
      "mid": "0001Pp4D0KQRBB",
      "name": "Stupid Love"
     }
    },
    {
     "id": 1283583,
     "mid": "003FYrtg1OnUtp",
     "name": "Stupid Love",
     "title": "Stupid Love",
     "interval": 186,
     "singer": [
      {
       "id": 12053,
       "mid": "000Fkxvn4bFNjR",
       "name": "超新星"
      }
     ],
     "album": {
      "id": 105515,
      "mid": "002eWkQD0Vimme",
      "name": "Stupid Love"
     }
    },
    {
     "id": 648186276,
     "mid": "000qkaJB1T3Z0v",
     "name": "STUPID LOVE",
     "title": "STUPID LOVE (MV版)",
     "interval": 154,
     "singer": [
      {
       "id": 10014877,
       "mid": "003g4SiJ3zTVAZ",
       "name": "종섭"
      }
     ],
     "album": {
      "id": 0,
      "mid": "",
      "name": ""
     }
    },
    {
     "id": 378875895,
     "mid": "000AdUxB0WVFas",
     "name": "진심이었던 사람만 바보가 돼 (Stupid Love)",
     "title": "진심이었던 사람만 바보가 돼 (Stupid Love)",
     "interval": 265,
     "singer": [
      {
       "id": 181896,
       "mid": "003YEuq92K9mBV",
       "name": "权珍雅"
      }
     ],
     "album": {
      "id": 31307411,
      "mid": "000eYZeN3TmnOh",
      "name": "진심이었던 사람만 바보가 돼 (Stupid Love)"
     }
    },
    {
     "id": 5175564,
     "mid": "004buSXd4LIYdV",
     "name": "Stupid Love (feat. Crush)",
     "title": "Stupid Love (feat. Crush)",
     "interval": 220,
     "singer": [
      {
       "id": 58693,
       "mid": "003AlLNF1Y15bH",
       "name": "Rhythm Power"
      },
      {
       "id": 164859,
       "mid": "000iYmqU22mp2u",
       "name": "Crush"
      }
     ],
     "album": {
      "id": 455766,
      "mid": "000AUPms2aYS9d",
      "name": "월미도의 개들"
     }
    },
    {
     "id": 420120841,
     "mid": "000ybQTm4gYQKX",
     "name": "stupid love-A project",
     "title": "stupid love-A project",
     "interval": 200,
     "singer": [
      {
       "id": 3751636,
       "mid": "0038tmkk2oKOPJ",
       "name": "JBcob"
      },
      {
       "id": 3885206,
       "mid": "001JCGP60Akfs0",
       "name": "ksovii"
      }
     ],
     "album": {
      "id": 39599844,
      "mid": "00255FKa1ZcZAG",
      "name": "LuvLuvLuvvy"
     }
    },
    {
     "id": 214075432,
     "mid": "000ONec02a7RNm",
     "name": "Stupid Love",
     "title": "Stupid Love",
     "interval": 194,
     "singer": [
      {
       "id": 174636,
       "mid": "000oI6bM0IeCg0",
       "name": "Dan + Shay"
      }
     ],
     "album": {
      "id": 4072057,
      "mid": "002cbO3s3tH7GD",
      "name": "Dan + Shay"
     }
    },
    {
     "id": 591084384,
     "mid": "0042EzSa2DuQBU",
     "name": "stupid  love",
     "title": "stupid  love",
     "interval": 198,
     "singer": [
      {
       "id": 2338411,
       "mid": "0025IFbw2uMN7H",
       "name": "欧阳宏 (Oyungh)"
      }
     ],
     "album": {
      "id": 71164405,
      "mid": "002qBYQu1zoK5m",
      "name": "stupid love"
     }
    }
   ]
  }
 }
}"""

    val searchCpWhatWasThatKatakanaJson: String = """{
 "code": 0,
 "data": {
  "keyword": "What Was That ロード",
  "song": {
   "curnum": 10,
   "curpage": 1,
   "totalnum": 2007,
   "list": [
    {
     "id": 264703,
     "mid": "001LWKsA3ICK2v",
     "name": "爱的故事 (上集)",
     "title": "爱的故事 (上集)",
     "interval": 239,
     "singer": [
      {
       "id": 263,
       "mid": "0000lM943KCK0L",
       "name": "孙耀威"
      }
     ],
     "album": {
      "id": 22539,
      "mid": "0044qN8G3hm2FM",
      "name": "爱的故事 (上集)"
     }
    },
    {
     "id": 328666897,
     "mid": "003d1hPh2utFg4",
     "name": "What Was That? (Scary Cave)",
     "title": "What Was That? (Scary Cave)",
     "interval": 208,
     "singer": [
      {
       "id": 16702198,
       "mid": "000zARCf20x1VK",
       "name": "SIEサウンドチーム"
      }
     ],
     "album": {
      "id": 22779665,
      "mid": "004eFBah0P0FD7",
      "name": "ASTRO BOT RESCUE MISSION Original Soundtrack"
     }
    },
    {
     "id": 5109502,
     "mid": "000TPQz12DosTC",
     "name": "爱的故事上集",
     "title": "爱的故事上集 (Live)",
     "interval": 238,
     "singer": [
      {
       "id": 219,
       "mid": "003AfDK34H82GU",
       "name": "张敬轩"
      },
      {
       "id": 263,
       "mid": "0000lM943KCK0L",
       "name": "孙耀威"
      }
     ],
     "album": {
      "id": 449281,
      "mid": "003v6a242dj0Sf",
      "name": "轩动心弦演唱会"
     }
    },
    {
     "id": 2966129,
     "mid": "003j5YDM3JUO1x",
     "name": "End Of The Road",
     "title": "End Of The Road",
     "interval": 349,
     "singer": [
      {
       "id": 7104,
       "mid": "002vIqoc0yqNIT",
       "name": "Boyz II Men"
      }
     ],
     "album": {
      "id": 239080,
      "mid": "00010J3v16Y4lv",
      "name": "Legacy - The Greatest Hits Collection"
     }
    },
    {
     "id": 233071612,
     "mid": "0024u6DY4XgXlz",
     "name": "Love Is A Long Road",
     "title": "Love Is A Long Road",
     "interval": 246,
     "singer": [
      {
       "id": 1652,
       "mid": "000j4P9p4T4Rrr",
       "name": "Tom Petty"
      }
     ],
     "album": {
      "id": 6966205,
      "mid": "004B9Lvu3Xr39u",
      "name": "Full Moon Fever"
     }
    },
    {
     "id": 586058642,
     "mid": "004dEuVU49ALZe",
     "name": "What Was That",
     "title": "What Was That",
     "interval": 209,
     "singer": [
      {
       "id": 164101,
       "mid": "002v4cvG2dhjzo",
       "name": "Lorde"
      }
     ],
     "album": {
      "id": 69740005,
      "mid": "0045sv5Y0vjsu2",
      "name": "Virgin (Explicit)"
     }
    },
    {
     "id": 1479245,
     "mid": "0002FXex4TjWDB",
     "name": "カントリー・ロード",
     "title": "カントリー・ロード (乡间小道)",
     "interval": 264,
     "singer": [
      {
       "id": 56499,
       "mid": "001LFiFu1Bw3Vk",
       "name": "野见祐二"
      }
     ],
     "album": {
      "id": 119802,
      "mid": "002TyB0P496JVv",
      "name": "耳をすませば サウンドトラック"
     }
    },
    {
     "id": 1172861,
     "mid": "003ZiBVh1mHi4E",
     "name": "ロード",
     "title": "ロード (Road)",
     "interval": 270,
     "singer": [
      {
       "id": 23767,
       "mid": "001mF6w238OsRs",
       "name": "THE 虎舞竜"
      }
     ],
     "album": {
      "id": 4269345,
      "mid": "004TdfkT2PpNB7",
      "name": "ロード～ドキュメント"
     }
    },
    {
     "id": 1419903,
     "mid": "003pF2rK0jff36",
     "name": "Further On Up The Road",
     "title": "Further On Up The Road (Album Version)",
     "interval": 204,
     "singer": [
      {
       "id": 10454,
       "mid": "000oGR9q23tZw7",
       "name": "Johnny Cash"
      }
     ],
     "album": {
      "id": 115090,
      "mid": "002saWam3oed0y",
      "name": "American V: A Hundred Highways"
     }
    },
    {
     "id": 729509917,
     "mid": "003JEQgm0zm1ke",
     "name": "What Was That",
     "title": "What Was That",
     "interval": 210,
     "singer": [
      {
       "id": 20617840,
       "mid": "0029oo1w1BBOjM",
       "name": "Chartwave Collective"
      }
     ],
     "album": {
      "id": 103590910,
      "mid": "0019o5fY3Gsksw",
      "name": "Twilight Haze (Explicit)"
     }
    }
   ]
  }
 }
}"""

    val songDetail97773Json: String = """{
 "code": 0,
 "data": [
  {
   "id": 97773,
   "mid": "0039MnYb0qxYhV",
   "name": "晴天",
   "title": "晴天",
   "interval": 269
  }
 ]
}"""
}

internal object HuaweiFixtures {
    val blindingLightsLrc: String = """[00:00.00]Blinding Lights - The Weeknd
[00:00.00]Lyrics by : Max Martin/Oscar Holter/Abel "The Weeknd" Tesfaye/Ahmad Balshe/Jason "DaHeala" Quenneville
[00:00.00]Composed by : Max Martin/Oscar Holter/Abel "The Weeknd" Tesfaye/Ahmad Balshe/Jason "DaHeala" Quenneville
[00:14.07]Yeah
[00:27.38]I've been tryna call ^我一直试着给你打电话
[00:29.95]I've been on my own for long enough ^我一个人孤单太久了
"""

    val instrumentalLrc: String = """[00:00:00]此歌曲为没有填词的纯音乐"""

    val lemonLrc: String = """[00:00.00]Lemon (《Unnatural》日剧主题曲) - 米津玄師 (よねづ けんし)
[00:00.00]词：米津玄師
[00:00.00]曲：米津玄師
[00:01.60]夢ならば^如果只是一场梦
[00:02.50]どれほどよかったでしょう^那该有多好
"""

    val nosuchkeyXml: String = """<?xml version='1.0' encoding='utf-8' ?>
<Error>
	<Code>NoSuchKey</Code>
	<Message>The specified key does not exist.</Message>
	<Resource>/lyric/75/802/75802077-LRC-LRC.lrc</Resource>
	<RequestId>NmFjMmUwZTNfYzRhZDY0MDlfMTc0MjFfMTAyMDMxODg=</RequestId>
	<TraceId>OGVmYzZiMmQzYjA2OWNhODk0NTRkMTBiOWVmMDAxODc0OWRkZjk0ZDM1NmI1M2E2MTRlY2MzZDhmNmI5MWI1OWEzMDE4ZGI4NTE5MWE4YmYxODQ1NWI3YzFiOTg0ZjdlODlmOGU2Y2Y3NmI3NmU0MmZiZTVhOTU1YmI0NjM4ZWU=</TraceId>
</Error>

"""

    val searchBlindingLightsJson: String = """{
 "result": {
  "resultCode": "000000",
  "resultMessage": "Success!"
 },
 "sortedContentTypes": [
  "1"
 ],
 "hasNextPage": "1",
 "totalNum": "21",
 "songSimpleInfos": [
  {
   "contentID": "MSZAMhtEID2dggSuc",
   "contentName": "Blinding Lights",
   "contentType": "1",
   "artistName": "The Weeknd",
   "albumName": "The Highlights",
   "subTitle": "The Weeknd - The Highlights",
   "lyricAddress": "https://music-pics-drcn.dbankcdn.cn/c79f27f1099d33cf774ef29ea024247e/fccc85b2037b9d71f82f28a3a433384d/36df5a58-61d9-4ae6-80c4-2e8a06756e3f.lrc",
   "contentExInfo": "{\"artistNames\":\"The Weeknd\",\"lyricAddres\":\"https://music-pics-drcn.dbankcdn.cn/c79f27f1099d33cf774ef29ea024247e/fccc85b2037b9d71f82f28a3a433384d/36df5a58-61d9-4ae6-80c4-2e8a06756e3f.lrc\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/c79f27f1099d33cf774ef29ea024247e/fccc85b2037b9d71f82f28a3a433384d/36df5a58-61d9-4ae6-80c4-2e8a06756e3f.lrc\\\",\\\"lineByLineLyric_subType\\\":\\\"translate|scrolling\\\",\\\"wordByWordLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/67020b6e711556843ab8b4d733087b70/c79f27f1099d33cf774ef29ea024247e/MSZAMhtEID2dggSuc_7eb4cceb-0c2b-45b6-acc1-600dc3fae97c.hmrc\\\"}\",\"fileInfos\":[{\"duration\":\"200\"}]}",
   "childContents": [
    {
     "contentID": "M3dqdXU5rrFVNHZN0",
     "contentName": "Blinding Lights",
     "contentType": "1",
     "artistName": "The Weeknd",
     "albumName": "After Hours",
     "subTitle": "The Weeknd - After Hours",
     "lyricAddress": "https://music-pics-drcn.dbankcdn.cn/dc433d6d1fe946e214907ef73d43f9d9/fccc85b2037b9d71f82f28a3a433384d/4f180ce0-5f1a-4127-8a02-915966b67977.lrc",
     "contentExInfo": "{\"artistNames\":\"The Weeknd\",\"lyricAddres\":\"https://music-pics-drcn.dbankcdn.cn/dc433d6d1fe946e214907ef73d43f9d9/fccc85b2037b9d71f82f28a3a433384d/4f180ce0-5f1a-4127-8a02-915966b67977.lrc\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/dc433d6d1fe946e214907ef73d43f9d9/fccc85b2037b9d71f82f28a3a433384d/4f180ce0-5f1a-4127-8a02-915966b67977.lrc\\\",\\\"lineByLineLyric_subType\\\":\\\"translate|scrolling\\\"}\",\"fileInfos\":[{\"duration\":\"200\"}]}"
    }
   ]
  },
  {
   "contentID": "M6B8NFVXH3Nq-NS70",
   "contentName": "Blinding Lights",
   "contentType": "1",
   "artistName": "The Weeknd",
   "albumName": "After Hours (Deluxe)",
   "subTitle": "The Weeknd - After Hours (Deluxe)",
   "lyricAddress": "https://music-pics-drcn.dbankcdn.cn/b8980f46062d6662699694931b54ccd3/fccc85b2037b9d71f82f28a3a433384d/2222a32d-8edb-4667-a3fc-c0b87bd6bf85.lrc",
   "contentExInfo": "{\"artistNames\":\"The Weeknd\",\"lyricAddres\":\"https://music-pics-drcn.dbankcdn.cn/b8980f46062d6662699694931b54ccd3/fccc85b2037b9d71f82f28a3a433384d/2222a32d-8edb-4667-a3fc-c0b87bd6bf85.lrc\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/b8980f46062d6662699694931b54ccd3/fccc85b2037b9d71f82f28a3a433384d/2222a32d-8edb-4667-a3fc-c0b87bd6bf85.lrc\\\",\\\"lineByLineLyric_subType\\\":\\\"translate|scrolling\\\"}\",\"fileInfos\":[{\"duration\":\"200\"}]}"
  }
 ]
}
"""

    val searchEmptyJson: String = """{
 "result": {
  "resultCode": "000000",
  "resultMessage": "Success!"
 },
 "sortedContentTypes": [
  "1"
 ],
 "hasNextPage": "0",
 "quResult": {},
 "totalNum": "0"
}
"""

    val searchLemonJson: String = """{
 "result": {
  "resultCode": "000000",
  "resultMessage": "Success!"
 },
 "sortedContentTypes": [
  "1"
 ],
 "hasNextPage": "1",
 "totalNum": "200",
 "songSimpleInfos": [
  {
   "contentID": "14584432",
   "contentName": "Lemon",
   "contentType": "1",
   "artistName": "米津玄師(よねづ けんし)",
   "albumName": "レモン(柠檬)",
   "subTitle": "米津玄師(よねづ けんし) - レモン(柠檬)",
   "lyricAddress": "https://music-pics-drcn.dbankcdn.cn/4c23bb0d6931ddf3fd18fed3a0a8372e/fccc85b2037b9d71f82f28a3a433384d/72a33102-7b76-4d58-a46c-fa533fd11a99.lrc",
   "contentExInfo": "{\"artistNames\":\"米津玄師(よねづ けんし)\",\"lyricAddres\":\"https://music-pics-drcn.dbankcdn.cn/4c23bb0d6931ddf3fd18fed3a0a8372e/fccc85b2037b9d71f82f28a3a433384d/72a33102-7b76-4d58-a46c-fa533fd11a99.lrc\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/4c23bb0d6931ddf3fd18fed3a0a8372e/fccc85b2037b9d71f82f28a3a433384d/72a33102-7b76-4d58-a46c-fa533fd11a99.lrc\\\",\\\"lineByLineLyric_subType\\\":\\\"translate|scrolling\\\",\\\"wordByWordLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/67020b6e711556843ab8b4d733087b70/4c23bb0d6931ddf3fd18fed3a0a8372e/14584432_f3bb8a17-6b6b-4179-8bf7-d451d3ed8b47.hmrc\\\"}\",\"fileInfos\":[{\"duration\":\"258\"}]}",
   "childContents": [
    {
     "contentID": "34893867",
     "contentName": "Lemon",
     "contentType": "1",
     "artistName": "米津玄師(よねづ けんし)",
     "albumName": "STRAY SHEEP",
     "subTitle": "米津玄師(よねづ けんし) - STRAY SHEEP",
     "lyricAddress": "https://lyric.tingmall.com/lyric/34/893/34893867-LRC-LRC.lrc?t=1596882207000",
     "contentExInfo": "{\"artistNames\":\"米津玄師(よねづ けんし)\",\"lyricAddres\":\"https://lyric.tingmall.com/lyric/34/893/34893867-LRC-LRC.lrc?t=1596882207000\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://lyric.tingmall.com/lyric/34/893/34893867-LRC-LRC.lrc?t=1596882207000\\\",\\\"lineByLineLyric_subType\\\":\\\"translate|scrolling\\\",\\\"wordByWordLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/c3ec257bb77d622aa1f5b9b51932f75d/b1bdddd7742532b6be2449efb4a26971/1834e251-d369-41e0-bf23-ea6637376763.hmrc\\\"}\",\"fileInfos\":[{\"duration\":\"255\"}]}"
    }
   ]
  },
  {
   "contentID": "102604819",
   "contentName": "Lemon(米津玄師)",
   "contentType": "1",
   "artistName": "コロムビアオルゴール",
   "albumName": "星と月のオルゴール",
   "subTitle": "コロムビアオルゴール - 星と月のオルゴール",
   "lyricAddress": "https://lyric.tingmall.com/lyric/102/604/102604819-LRC-LRC.lrc?t=1773366729000",
   "contentExInfo": "{\"artistNames\":\"コロムビアオルゴール\",\"lyricAddres\":\"https://lyric.tingmall.com/lyric/102/604/102604819-LRC-LRC.lrc?t=1773366729000\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://lyric.tingmall.com/lyric/102/604/102604819-LRC-LRC.lrc?t=1773366729000\\\",\\\"lineByLineLyric_subType\\\":\\\"abs\\\"}\",\"fileInfos\":[{\"duration\":\"280\"}]}"
  },
  {
   "contentID": "MFAkyyYXn9TxOlrAp",
   "contentName": "Lemon(翻自：米津玄師)",
   "contentType": "1",
   "artistName": "Vk",
   "albumName": "Lemon",
   "subTitle": "Vk - Lemon",
   "lyricAddress": "https://music-pics-drcn.dbankcdn.cn/b08d70869aba5e6830ea6851f6283206/fccc85b2037b9d71f82f28a3a433384d/0c861e18-5acc-468c-80aa-fd37ec96bafa.lrc",
   "contentExInfo": "{\"artistNames\":\"Vk\",\"lyricAddres\":\"https://music-pics-drcn.dbankcdn.cn/b08d70869aba5e6830ea6851f6283206/fccc85b2037b9d71f82f28a3a433384d/0c861e18-5acc-468c-80aa-fd37ec96bafa.lrc\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/b08d70869aba5e6830ea6851f6283206/fccc85b2037b9d71f82f28a3a433384d/0c861e18-5acc-468c-80aa-fd37ec96bafa.lrc\\\",\\\"lineByLineLyric_subType\\\":\\\"translate|scrolling\\\",\\\"wordByWordLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/67020b6e711556843ab8b4d733087b70/b08d70869aba5e6830ea6851f6283206/MFAkyyYXn9TxOlrAp_b48a086c-0fba-426c-a411-084b99fef98f.hmrc\\\"}\",\"fileInfos\":[{\"duration\":\"255\"}]}"
  }
 ]
}
"""

    val searchPretenderJson: String = """{
 "result": {
  "resultCode": "000000",
  "resultMessage": "Success!"
 },
 "sortedContentTypes": [
  "1"
 ],
 "hasNextPage": "1",
 "totalNum": "146",
 "songSimpleInfos": [
  {
   "contentID": "51044549",
   "contentName": "Pretender(假装者)",
   "contentType": "1",
   "artistName": "Official髭男dism",
   "albumName": "Pretender",
   "subTitle": "Official髭男dism - Pretender",
   "lyricAddress": "https://music-pics-drcn.dbankcdn.cn/e50fd65a63213cbad8c2dc415cc4b988/fccc85b2037b9d71f82f28a3a433384d/51cb7c0c-992b-4678-ab5f-d4de26e7868f.lrc",
   "contentExInfo": "{\"artistNames\":\"Official髭男dism\",\"lyricAddres\":\"https://music-pics-drcn.dbankcdn.cn/e50fd65a63213cbad8c2dc415cc4b988/fccc85b2037b9d71f82f28a3a433384d/51cb7c0c-992b-4678-ab5f-d4de26e7868f.lrc\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/e50fd65a63213cbad8c2dc415cc4b988/fccc85b2037b9d71f82f28a3a433384d/51cb7c0c-992b-4678-ab5f-d4de26e7868f.lrc\\\",\\\"lineByLineLyric_subType\\\":\\\"translate|scrolling\\\",\\\"wordByWordLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/67020b6e711556843ab8b4d733087b70/e50fd65a63213cbad8c2dc415cc4b988/51044549_59d707aa-d286-4102-90ed-a0904863cae2.hmrc\\\"}\",\"fileInfos\":[{\"duration\":\"326\"}]}"
  },
  {
   "contentID": "62812123",
   "contentName": "Pretender(假扮者)",
   "contentType": "1",
   "artistName": "Official髭男dism",
   "albumName": "one-man tour 2021-2022 -Editorial-@さいたまスーパーアリーナ",
   "subTitle": "Official髭男dism - one-man tour 2021-2022 -Editorial-@さいたまスーパーアリーナ",
   "lyricAddress": "https://lyric.tingmall.com/lyric/62/812/62812123-LRC-LRC.lrc?t=1686304614000",
   "contentExInfo": "{\"artistNames\":\"Official髭男dism\",\"lyricAddres\":\"https://lyric.tingmall.com/lyric/62/812/62812123-LRC-LRC.lrc?t=1686304614000\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://lyric.tingmall.com/lyric/62/812/62812123-LRC-LRC.lrc?t=1686304614000\\\",\\\"lineByLineLyric_subType\\\":\\\"translate|scrolling\\\"}\",\"fileInfos\":[{\"duration\":\"344\"}]}"
  },
  {
   "contentID": "51045017",
   "contentName": "Pretender",
   "contentType": "1",
   "artistName": "Official髭男dism",
   "albumName": "Traveler",
   "subTitle": "Official髭男dism - Traveler",
   "lyricAddress": "https://music-pics-drcn.dbankcdn.cn/6bb61e3b7bce0931da574d19d1d82c88/1a82ddd79717f32c96c6686fc8ad0978/79c5ec6f-d69a-408d-ae44-3bf2f586bd57_lyric.txt",
   "contentExInfo": "{\"artistNames\":\"Official髭男dism\",\"lyricAddres\":\"https://music-pics-drcn.dbankcdn.cn/6bb61e3b7bce0931da574d19d1d82c88/1a82ddd79717f32c96c6686fc8ad0978/79c5ec6f-d69a-408d-ae44-3bf2f586bd57_lyric.txt\",\"lyrics\":\"{\\\"lineByLineLyric\\\":\\\"https://music-pics-drcn.dbankcdn.cn/6bb61e3b7bce0931da574d19d1d82c88/1a82ddd79717f32c96c6686fc8ad0978/79c5ec6f-d69a-408d-ae44-3bf2f586bd57_lyric.txt\\\",\\\"lineByLineLyric_subType\\\":\\\"scrolling\\\"}\",\"fileInfos\":[{\"duration\":\"326\"}]}"
  }
 ]
}
"""

    val uchiageHanabiBomCrlfLrc: String = "\uFEFF[ti:打上花火 (《升空的焰火，从下面看还是从侧面看？》动画电影主题曲)]\r\n[ar:DAOKO (ダヲコ)]\r\n[al:打上花火]\r\n[00:00.40]打上花火 (《升空的焰火，从下面看还是从侧面看？》动画电影主题曲) - DAOKO (ダヲコ)/米津玄師 (よねづ けんし)\r\n[00:06.69]词：米津玄師\r\n[00:07.92]曲：米津玄師\r\n[00:20.30]あの日見わたした渚^直到现在我也能想起\r\n[00:25.07]今も思い出すんだ^那天眺望到的海岸\r\n"
}

internal object NeteaseFixtures {
    val cloudsearchBlindingLightsVersionsJson: String = """{
 "result": {
  "songs": [
   {
    "id": 1500412585,
    "name": "Blinding Lights (Remix)",
    "ar": [
     {
      "id": 185858,
      "name": "The Weeknd"
     },
     {
      "id": 320153,
      "name": "ROSALÍA"
     }
    ],
    "al": {
     "name": "Blinding Lights (Remix)"
    },
    "dt": 216123
   },
   {
    "id": 2027162148,
    "name": "Blinding Lights (Live)",
    "ar": [
     {
      "id": 185858,
      "name": "The Weeknd"
     }
    ],
    "al": {
     "name": "After Hours (Live At SoFi Stadium)"
    },
    "dt": 253365
   },
   {
    "id": 1478647776,
    "name": "Blinding Lights (Instrumental)",
    "ar": [
     {
      "id": 185858,
      "name": "The Weeknd"
     }
    ],
    "al": {
     "name": "Blinding Lights"
    },
    "dt": 202104
   },
   {
    "id": 1433194293,
    "name": "Blinding Lights",
    "ar": [
     {
      "id": 185858,
      "name": "The Weeknd"
     }
    ],
    "al": {
     "name": "After Hours (Deluxe)"
    },
    "dt": 200045
   }
  ]
 },
 "code": 200
}
"""

    val cloudsearchHotelCaliforniaJson: String = """{
 "result": {
  "songs": [
   {
    "id": 26289183,
    "name": "Hotel California (2013 Remaster)",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "The Studio Albums 1972-1979 (2013 Remaster)"
    },
    "dt": 391376
   },
   {
    "id": 4049399,
    "name": "Hotel California (Live on MTV, 1994)",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "Hell Freezes Over"
    },
    "dt": 432040
   },
   {
    "id": 1318509823,
    "name": "Hotel California (1999 Remaster)",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "Selected Works 1972-1999"
    },
    "dt": 390266
   },
   {
    "id": 1302701468,
    "name": "Hotel California",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "Unplugged 1994 - The Second Night"
    },
    "dt": 446560
   },
   {
    "id": 1461046602,
    "name": "Hotel California (Live From The Forum, Inglewood, CA, 9/12, 14, 15/2018)",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "Hotel California (Live From The Forum, Inglewood, CA, 9/12, 14, 15/2018)"
    },
    "dt": 507373
   },
   {
    "id": 1963423447,
    "name": "Hotel California (live)",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "Unplugged 1994 (live)"
    },
    "dt": 440706
   },
   {
    "id": 2698477673,
    "name": "Hotel California (Live, 1976)",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "5ive of a Kind - Hotel California (Live)"
    },
    "dt": 390373
   },
   {
    "id": 2698479305,
    "name": "Hotel California (Live, 1994)",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "5ive of a Kind - Hotel California (Live)"
    },
    "dt": 339847
   },
   {
    "id": 531954630,
    "name": "Hotel California",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "The Summit, Houston, 1976 (Hd Remastered Edition)"
    },
    "dt": 422320
   },
   {
    "id": 514158843,
    "name": "Hotel California (Live at The Forum, Los Angeles, CA, 10/20-22/1976)",
    "ar": [
     {
      "id": 91725,
      "name": "Eagles"
     }
    ],
    "al": {
     "name": "Hotel California (40th Anniversary Expanded Edition)"
    },
    "dt": 409520
   }
  ]
 },
 "code": 200
}
"""

    val cloudsearchLemonJson: String = """{
 "result": {
  "songs": [
   {
    "id": 536622304,
    "name": "Lemon",
    "ar": [
     {
      "id": 159300,
      "name": "米津玄師"
     }
    ],
    "al": {
     "name": "Lemon"
    },
    "dt": 256000
   },
   {
    "id": 1344729444,
    "name": "lemon（钢琴改编版）（翻自 米津玄師）",
    "ar": [
     {
      "id": 0,
      "name": "米津玄師"
     }
    ],
    "al": {
     "name": "把各种有名的流行歌曲（或纯音乐）改成大（小）调等各种调式会怎么样呢？"
    },
    "dt": 87536
   },
   {
    "id": 1344727797,
    "name": "lemon（改大调）（翻自 米津玄師）",
    "ar": [
     {
      "id": 0,
      "name": "米津玄師"
     }
    ],
    "al": {
     "name": "把各种有名的流行歌曲（或纯音乐）改成大（小）调等各种调式会怎么样呢？"
    },
    "dt": 86778
   },
   {
    "id": 1466598056,
    "name": "Lemon",
    "ar": [
     {
      "id": 159300,
      "name": "米津玄師"
     }
    ],
    "al": {
     "name": "STRAY SHEEP"
    },
    "dt": 255883
   },
   {
    "id": 569037886,
    "name": "Lemon（Cover 米津玄师）",
    "ar": [
     {
      "id": 1047064,
      "name": "KBShinya"
     }
    ],
    "al": {
     "name": "LEMON"
    },
    "dt": 254458
   },
   {
    "id": 1444387266,
    "name": "Lemon（伴奏）",
    "ar": [
     {
      "id": 0,
      "name": "米津玄师"
     }
    ],
    "al": {
     "name": "Lemon（伴奏）"
    },
    "dt": 256908
   },
   {
    "id": 1344614160,
    "name": "Lemon（原版）（翻自 米津玄師）",
    "ar": [
     {
      "id": 0,
      "name": "米津玄师"
     }
    ],
    "al": {
     "name": "把米津玄师的《lemon》改成大调会怎么样呢？"
    },
    "dt": 90096
   },
   {
    "id": 1939880626,
    "name": "lemon(cos：米津玄师)",
    "ar": [
     {
      "id": 0,
      "name": "米津玄师"
     },
     {
      "id": 52340646,
      "name": "韩少泽"
     }
    ],
    "al": {
     "name": "翻唱"
    },
    "dt": 255558
   },
   {
    "id": 1935931088,
    "name": "LEMON吉他（翻自 米津玄师）",
    "ar": [
     {
      "id": 52156162,
      "name": "赴约"
     },
     {
      "id": 0,
      "name": "米津玄师"
     }
    ],
    "al": {
     "name": "GT"
    },
    "dt": 245323
   },
   {
    "id": 1344613159,
    "name": "Lemon（改大调）（翻自 米津玄師）",
    "ar": [
     {
      "id": 0,
      "name": "米津玄师"
     }
    ],
    "al": {
     "name": "把米津玄师的《lemon》改成大调会怎么样呢？"
    },
    "dt": 89952
   }
  ]
 },
 "code": 200
}
"""

    val cloudsearchLetItGoJson: String = """{
 "result": {
  "songs": [
   {
    "id": 28031119,
    "name": "Let It Go (From \"Frozen\"/Soundtrack Version)",
    "ar": [
     {
      "id": 60841,
      "name": "Idina Menzel"
     }
    ],
    "al": {
     "name": "Frozen (Original Motion Picture Soundtrack / Deluxe Edition)"
    },
    "dt": 223840
   },
   {
    "id": 1314780199,
    "name": "Let It Go (Live)",
    "ar": [
     {
      "id": 60841,
      "name": "Idina Menzel"
     }
    ],
    "al": {
     "name": "idina: live"
    },
    "dt": 295973
   },
   {
    "id": 489768374,
    "name": "Let It Go (Corbin Hayes Remix)",
    "ar": [
     {
      "id": 60841,
      "name": "Idina Menzel"
     }
    ],
    "al": {
     "name": "Let It Go Remixes"
    },
    "dt": 408485
   },
   {
    "id": 28814175,
    "name": "Let It Go",
    "ar": [
     {
      "id": 53236,
      "name": "Demi Lovato"
     }
    ],
    "al": {
     "name": "冰雪奇缘 中文版电影原声带"
    },
    "dt": 227120
   },
   {
    "id": 28547079,
    "name": "Let It Go (DJ Escape & Tony Coluccio Club Remix)",
    "ar": [
     {
      "id": 60841,
      "name": "Idina Menzel"
     }
    ],
    "al": {
     "name": "Let It Go Remixes"
    },
    "dt": 506334
   },
   {
    "id": 28547077,
    "name": "Let It Go (Dave Audé Club Remix)",
    "ar": [
     {
      "id": 60841,
      "name": "Idina Menzel"
     }
    ],
    "al": {
     "name": "Let It Go Remixes"
    },
    "dt": 348995
   },
   {
    "id": 28547078,
    "name": "Let It Go (Papercha${'$'}er Club Remix)",
    "ar": [
     {
      "id": 60841,
      "name": "Idina Menzel"
     }
    ],
    "al": {
     "name": "Let It Go Remixes"
    },
    "dt": 291642
   },
   {
    "id": 28762798,
    "name": "Let It Go (Multi-Language Medley)",
    "ar": [
     {
      "id": 59108,
      "name": "Gisela"
     },
     {
      "id": 60841,
      "name": "Idina Menzel"
     },
     {
      "id": 12493407,
      "name": "Anaïs Delva"
     },
     {
      "id": 28798984,
      "name": "Willemijn Verkaik"
     },
     {
      "id": 29005795,
      "name": "Ana Encarnação"
     },
     {
      "id": 29005846,
      "name": "Elke Buyle"
     },
     {
      "id": 29471172,
      "name": "Carmen Sarahi"
     },
     {
      "id": 29528048,
      "name": "Annika Herlitz"
     },
     {
      "id": 29528578,
      "name": "朴惠娜"
     },
     {
      "id": 29528580,
      "name": "Serena Autieri"
     },
     {
      "id": 29727872,
      "name": "Füredi Nikolett"
     },
     {
      "id": 29727874,
      "name": "Jelena Gavrilovic"
     },
     {
      "id": 29727876,
      "name": "Takako Matsu"
     },
     {
      "id": 29727912,
      "name": "Jobelle Ubalde"
     },
     {
      "id": 29809827,
      "name": "Katarzyna Laska"
     },
     {
      "id": 29969334,
      "name": "Jalane Hu"
     }
    ],
    "al": {
     "name": "Let It Go (Multi-Language Medley)"
    },
    "dt": 223329
   },
   {
    "id": 489741185,
    "name": "Let It Go (Unplugged Piano Interpretation)",
    "ar": [
     {
      "id": 12767901,
      "name": "The Piano Kings"
     }
    ],
    "al": {
     "name": "Let It Go (Originally Performed By Idina Menzel) [Classic Piano Interpretations]"
    },
    "dt": 238288
   },
   {
    "id": 1353527069,
    "name": "Let It Go (Originally Performed by Idina Menzel)",
    "ar": [
     {
      "id": 31731169,
      "name": "Tunified"
     }
    ],
    "al": {
     "name": "Frozen (Karaoke Version)"
    },
    "dt": 218592
   }
  ]
 },
 "code": 200
}
"""

    val cloudsearchQingtianJson: String = """{
 "result": {
  "songs": [
   {
    "id": 2652820720,
    "name": "晴天(深情版)",
    "ar": [
     {
      "id": 96154669,
      "name": "Lucky小爱"
     }
    ],
    "al": {
     "name": "晴天(深情版)"
    },
    "dt": 278961
   },
   {
    "id": 3440441479,
    "name": "晴天",
    "ar": [
     {
      "id": 122200643,
      "name": "Jay"
     }
    ],
    "al": {
     "name": "晴天"
    },
    "dt": 112373
   },
   {
    "id": 2668397359,
    "name": "晴天 (原唱 周杰伦)",
    "ar": [
     {
      "id": 0,
      "name": "RyaVocal"
     }
    ],
    "al": {
     "name": "晴天"
    },
    "dt": 270738
   },
   {
    "id": 2653714443,
    "name": "晴天 (女声版))",
    "ar": [
     {
      "id": 50759987,
      "name": "GYBeat"
     }
    ],
    "al": {
     "name": "晴天 (女声版)"
    },
    "dt": 268750
   },
   {
    "id": 1945894789,
    "name": "晴天 (钢琴版) [原唱: 周杰伦]",
    "ar": [
     {
      "id": 52587505,
      "name": "纪钧瀚 (Bryan Chi)"
     }
    ],
    "al": {
     "name": "钢琴放松轻听 流行轻音乐 华语经典"
    },
    "dt": 238098
   },
   {
    "id": 3405047412,
    "name": "周杰伦“晴天”PopxTrap Beat",
    "ar": [
     {
      "id": 98544638,
      "name": "NYONTHEBEAT"
     }
    ],
    "al": {
     "name": "周杰伦“晴天”PopxTrap Beat"
    },
    "dt": 172224
   },
   {
    "id": 2757922584,
    "name": "晴天 (吉他版) (原唱：周杰伦)",
    "ar": [
     {
      "id": 96143877,
      "name": "郭衡 (Guo Heng)"
     }
    ],
    "al": {
     "name": "轻音乐咖啡 放松治愈的吉他BGM 华语流行与古典的专注纯音乐"
    },
    "dt": 186306
   },
   {
    "id": 2064191772,
    "name": "晴天周杰伦(从前从前有个人爱你很久)",
    "ar": [
     {
      "id": 56023906,
      "name": "沈幼楚"
     }
    ],
    "al": {
     "name": "不要你离开距离隔不开思念变成海"
    },
    "dt": 30974
   },
   {
    "id": 108914,
    "name": "江南",
    "ar": [
     {
      "id": 3684,
      "name": "林俊杰"
     }
    ],
    "al": {
     "name": "第二天堂"
    },
    "dt": 267946
   },
   {
    "id": 3391116218,
    "name": "晴天",
    "ar": [
     {
      "id": 123533149,
      "name": "梦里啥都有"
     }
    ],
    "al": {
     "name": "晴天"
    },
    "dt": 228545
   }
  ]
 },
 "code": 200
}
"""

    val cloudsearchStupidLoveKatakanaJson: String = """{
 "result": {
  "songs": [
   {
    "id": 2157493114,
    "name": "Stupid love (PARTY HITS EDIT)",
    "ar": [
     {
      "id": 53111686,
      "name": "Party Town"
     }
    ],
    "al": {
     "name": "オシャレな人がランニングで聴いてそうな アップテンポな洋楽"
    },
    "dt": 194780
   }
  ]
 },
 "code": 200
}
"""

    val cloudsearchWhatWasThatKatakanaJson: String = """{
 "result": {
  "songs": [
   {
    "id": 2079173646,
    "name": "Bittersweet",
    "ar": [
     {
      "id": 16195,
      "name": "BONNIE PINK"
     }
    ],
    "al": {
     "name": "Infinity"
    },
    "dt": 219932
   }
  ]
 },
 "code": 200
}
"""

    val cloudsearchYesterdayJson: String = """{
 "result": {
  "songs": [
   {
    "id": 4337372,
    "name": "Yesterday (Remastered)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "Help! (Remastered)"
    },
    "dt": 123576
   },
   {
    "id": 493156574,
    "name": "Yesterday",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     },
     {
      "id": 12597288,
      "name": "Marty Murray"
     }
    ],
    "al": {
     "name": "Famous Interviews - The Beatles"
    },
    "dt": 149506
   },
   {
    "id": 2138339820,
    "name": "Yesterday (Circus Krone-Bau, Germany 1966) (Live Broadcast)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "Live in Europe 1965 and 1966"
    },
    "dt": 159500
   },
   {
    "id": 4333142,
    "name": "Yesterday (Anthology 2 Version)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "Anthology 2"
    },
    "dt": 154110
   },
   {
    "id": 4335790,
    "name": "Yesterday (Remastered)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "The Beatles 1962 - 1966 (Remastered)"
    },
    "dt": 125666
   },
   {
    "id": 4333166,
    "name": "Yesterday (Anthology 2 Version / Live From The ABC Theatre, Blackpool, UK/1965)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "Anthology 2"
    },
    "dt": 162725
   },
   {
    "id": 2743876762,
    "name": "Yesterday (live)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "Live in Tokyo 1966"
    },
    "dt": 145400
   },
   {
    "id": 3322274456,
    "name": "Yesterday (Remastered 2015)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "Anthology: Soundtrack to the Disney+ Series (Episodes 4-6)"
    },
    "dt": 125320
   },
   {
    "id": 2741147596,
    "name": "Yesterday (Take 1 - Remastered)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "Anthology Highlights"
    },
    "dt": 153720
   },
   {
    "id": 2098372542,
    "name": "Yesterday (2023 Mix)",
    "ar": [
     {
      "id": 101988,
      "name": "The Beatles"
     }
    ],
    "al": {
     "name": "The Beatles 1962 – 1966 (2023 Edition)"
    },
    "dt": 124426
   }
  ]
 },
 "code": 200
}
"""
}

/** Fixture by its original Android file name. */
internal val QQ_FIXTURES: Map<String, String> = mapOf(
    "gateway_refused_2001.json" to QqFixtures.gatewayRefused2001Json,
    "gateway_search_qingtian.json" to QqFixtures.gatewaySearchQingtianJson,
    "legacy_lyric_qingtian_0039MnYb0qxYhV.json" to QqFixtures.legacyLyricQingtian0039MnYb0qxYhVJson,
    "legacy_lyric_qingtian_jsonp_base64.txt" to QqFixtures.legacyLyricQingtianJsonpBase64Txt,
    "legacy_lyric_refused_1101.json" to QqFixtures.legacyLyricRefused1101Json,
    "play_lyric_lemon_213086592.json" to QqFixtures.playLyricLemon213086592Json,
    "search_cp_qingtian.json" to QqFixtures.searchCpQingtianJson,
    "search_cp_stupid_love_katakana.json" to QqFixtures.searchCpStupidLoveKatakanaJson,
    "search_cp_what_was_that_katakana.json" to QqFixtures.searchCpWhatWasThatKatakanaJson,
    "song_detail_97773.json" to QqFixtures.songDetail97773Json,
)

/** Fixture by its original Android file name. */
internal val HUAWEI_FIXTURES: Map<String, String> = mapOf(
    "blinding_lights.lrc" to HuaweiFixtures.blindingLightsLrc,
    "instrumental.lrc" to HuaweiFixtures.instrumentalLrc,
    "lemon.lrc" to HuaweiFixtures.lemonLrc,
    "nosuchkey.xml" to HuaweiFixtures.nosuchkeyXml,
    "search_blinding_lights.json" to HuaweiFixtures.searchBlindingLightsJson,
    "search_empty.json" to HuaweiFixtures.searchEmptyJson,
    "search_lemon.json" to HuaweiFixtures.searchLemonJson,
    "search_pretender.json" to HuaweiFixtures.searchPretenderJson,
    "uchiage_hanabi_bom_crlf.lrc" to HuaweiFixtures.uchiageHanabiBomCrlfLrc,
)

/** Fixture by its original Android file name. */
internal val NETEASE_FIXTURES: Map<String, String> = mapOf(
    "cloudsearch_blinding_lights_versions.json" to NeteaseFixtures.cloudsearchBlindingLightsVersionsJson,
    "cloudsearch_hotel_california.json" to NeteaseFixtures.cloudsearchHotelCaliforniaJson,
    "cloudsearch_lemon.json" to NeteaseFixtures.cloudsearchLemonJson,
    "cloudsearch_let_it_go.json" to NeteaseFixtures.cloudsearchLetItGoJson,
    "cloudsearch_qingtian.json" to NeteaseFixtures.cloudsearchQingtianJson,
    "cloudsearch_stupid_love_katakana.json" to NeteaseFixtures.cloudsearchStupidLoveKatakanaJson,
    "cloudsearch_what_was_that_katakana.json" to NeteaseFixtures.cloudsearchWhatWasThatKatakanaJson,
    "cloudsearch_yesterday.json" to NeteaseFixtures.cloudsearchYesterdayJson,
)
