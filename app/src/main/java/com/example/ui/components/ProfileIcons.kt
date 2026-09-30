@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.tv.foundation.ExperimentalTvFoundationApi::class)
package com.example.ui.components

/**
 * One themed group of profile-avatar URLs (e.g. "Stranger Things", "Squid Game").
 *
 * Used by `EditProfileScreen` to render the icon-picker carousel. The [id] is
 * stable across releases and is a safe lazy-list key.
 *
 * @property id stable identifier (used as Compose key).
 * @property title human-readable category title shown beside the row.
 * @property icons ordered list of remote PNG URLs.
 */
data class ProfileIconCategory(
    val id: String,
    val title: String,
    val icons: List<String>
)

/**
 * Static library of profile avatar icons, grouped into themed
 * [ProfileIconCategory]s and additionally flattened into [ICONS] for callers
 * that just want "the next available avatar" (e.g. default profile creation).
 *
 * All values are `val` lists, so the strings are allocated exactly once at class
 * load and shared across every profile screen. The [ICONS] list is `distinct()`-ed
 * at init so duplicate URLs across categories only appear once in the flat list.
 */
object ProfileIcons {
    // These legacy batch names and per-image annotations do not match the
    // artwork currently returned by many URLs. CATEGORIES below defines the
    // grouping shown in the picker.
    // 1. The Classics
    val CLASSICS = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTmqXTOMU-F0KZgOVnOOMmQgywysKJFFucBikFXp8sQHsvJeMMnNAymzlYZcH5nOt3j18egkX0cy5NQAZ3Yxiqq5v5ZI9wg42Q.png?r=8e0", // Red Smile
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABQ-qpyR4ZQV8gJ34aN0mVfOwjMiuUQRYDoQl05M27vi7xTDX7Vj-LyVO6mj82q3QF5eMS6ncbkggYVcndKS-Y0_O_SVvRkp06Q.png?r=37e", // Blue Smile
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABbGFW3wQDT_CzSAJFfnzBvw4vG6BnjXT_igdOgKY-zGwjrL0HjFBZOrF7JGIQDWaThvIF-yJ_iVcyxp6WLWJ_5db4Rc1sQut2g.png?r=7ed", // Yellow Smile
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABasJVclPLNN7SlMkyLXXFj5k1FJZexnIXhXPsCw4EynmXUV97IuGo4sRIhrjqJXp_aynS7WRZ54839GyXK_t6u_M0dUBt4skcg.png?r=c1a", // Cyan Panda
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABS55oIOf-6CREy8MrUBTVPEyhcS-ldOK2O8Rl7_RxcBMPWPyS5AMzi1yyjNQkjg95wc1vKSRpMMsVSWhRuleL7Heix85NUtWAw.png?r=d5b", // Red Angry
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABVRYuJZCfePzAa7SDjBM7mR7UL-QSdV1Se5f7skOSkTqqZzjQbjwOHovOWHgm0EuDGb-kmyVVz5UklMt5Ll9ygPg6d6cKSia3A.png?r=236", // Purple Penguin
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABVOUc4gXCizdZZ_pTe97G7nf7FRR94-d6ktp2BEFIPk_EQIOrttuxm6Znt02MwQ--i5C_IR0nDoK0iCByTgb3g71TmbTDjgxBQ.png?r=9fc", // Pink Cat / Unicorn
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABYGaO3vHkB8_0x4ieXzNZAQzK-sAbnh58P9AhvCsG1In49O3EQk7eAIAd5X5Qx4S-S_i3XO_r2alPR6otrhBzDsYq6fQ7pMH1g.png?r=aae", // Yellow Chicken
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABc9Sea_6JFA55mL8Zu_I8jwJTDIuyP52A5_1UtzzjyRaZppmdul8CaUQk7O7k-nDJQqm_98Yz7CEDUo_ZB1rzliyl85QjYvEKw.png?r=c1a", // Blue Teal Face
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABXFk3CCEe-AhhW56UJUyNU88dHKVedfSFG63P6mp-asuKTDO8beQ_kZL39YJZlqhL--bmXMwuSVcWP4w6Dyw6HgAlKTzs0BMeA.png?r=c3d", // Green Smile
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABfUNI1Zw5bAZ2N9nVtRRX8U28Z_m4p0vLYWk7Ml3AWTHuFkLy6R-PesvLc52L_y4AIIWMLxQO1WkeTRDsSg0o1Nvdm7mMy4jEA.png?r=db3", // Magenta Wink
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABXFesvcDr34Y-p8KDVREU2Nog4uRrk6eLG5sNPt-neZ2zo1SntHY-sdQhdYuNygZhvPlWfmMd0vtchahlB89G1BunDbdzucuFg.png?r=001"  // Purple Smile
    )

    // 2. History / Recently Used
    val HISTORY = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTmqXTOMU-F0KZgOVnOOMmQgywysKJFFucBikFXp8sQHsvJeMMnNAymzlYZcH5nOt3j18egkX0cy5NQAZ3Yxiqq5v5ZI9wg42Q.png?r=8e0",
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABVMMwQfW2Dzg6AvTi0gg4-GUb5hgxZWlr9Uq6nL7ssV6UP6zQERgu1MrzkIfEkO07fOaGk92KLePKspVD-cSEq9WSiCAFH8FUw.png?r=8c0",
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABbg1ZlIcmmeFdjrUIU6phOUWqYDaoDcM8byp20X2HSNMTqW8NE4JftwFXt92hhI-qMq-vrnN_m94pa7UyfrU6Kc82ElLl82Nhg.png?r=36d",
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABYRT0TFZeOxM6R5s11VIJVUAJmje3XdzzmQxKcePrsNoFN9eY_JtEi1YOPsi48wUobz19IO-Q6xRD97KQoNv2swxFMcR83qBLQ.png?r=4e5",
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABaZnvORvYTyXI3lGpg_DcQOxRKU_mglsTpQepUqijTtmSPehLCMMWiY4LWI-5aDgZz3bOb166deKJU53YHe_YveM_s_gFGWo9A.png?r=8ea"
    )

    // 3. COBRA KAI
    val COBRA_KAI = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTmJTNXbx-S7txYriDqzXIdv3a7eKMdA-OCNUFLGCcMIzrishbZmMgbsvGaojMkgsTkRCndFUuqzwPi1SwPh6qwKED_4U1dJTA.png?r=94e", // Johnny Lawrence
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABeHnpYAidb-9WpclwBS281U07ZA_SsG_CzAPyMVl6aOXVtaUHzrUsVPHraGZbLrn2EMSa9renMcG6gCCo5RTeZ6NspsITeSWwA.png?r=226", // Daniel LaRusso
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABQG_JDg1HRfTsqLJnyhIGoXiFMGXjtQ5PDvsJ5mFfbR_3RYI5m2gf5EKK8s-VCq_gELKsjX62Q-I-exN0hM6Tofb-Q8wzRbkFQ.png?r=7e8", // Miguel Diaz
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABZ54PIG1YI_Jm8al3ME0HXH9tJ0_taRaSHf9oN3x0Q5TSm_1P7RksR6kpGxaHPTEOkD0gAyH1tMi73Uz9UvHhdefPihHFUSPvg.png?r=f54", // Robby Keene
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABdlk_fIvMoAj_U3aDL5AHibzKqiLht3gT2ZJFNuC5rLx9BU6tcvMhlrMdN9MIyfdaFOMqEYokkTVkThxJrb8dIxyXRELj6HC4A.png?r=9ce", // Tory Nichols
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABY9Fs6RVMJvHWrLjWsRKoepddeVhTspOKB4pOGVHhLzwZdjpq975b5CmDyZRY5y3a9SIg8LFnEGoLkBK44znCWpElIRW1H_Rjg.png?r=c83", // Samantha LaRusso
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABe-Qp6Jo89KchcH5lDTmD7xqUTDuFWuxxBduweUTvouhBq0gdcbYbaZW9_aJtD3cXRWxVbRO2lshVQ1EHVSr5RjymaE8F07FnA.png?r=0d3", // Hawk
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABVwWFFylamZb4YND8HkuhNOCLE7UyD4BZ-8FEYb_BwmfxSz0E92gaHeafrVC5QN_VwmzKl3T9SOEaCRv82iZcMpBJsy02HpINQ.png?r=177"  // John Kreese
    )

    // 4. Stranger Things
    val STRANGER_THINGS = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABal0ZPMTfbxv3zf6kfQ9VsMloiKGeu1kJ1edI3ggHJwwscq4udVH8FRUrUn03xXNrF60FvkgUOWjhAhT7cnApMQimt81h64hPg.png?r=ab4", // Eleven
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTlTU-mHDamDrVzbpZ5vy66jsIpyCWue8RFHYzdwWm9ZbMlJq8Xuw8XnUa0eBtQJSLs8yRO_q0KoUmYFwWIfEqVV0LcHpUBA2g.png?r=ac3", // Dustin Henderson
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABUYgtneeSpGoet370wTIs1C58AK0jNi4zQHw276wGNEJf4vGO99bIFY0dCsuBqo5D_VOgBuv-m4YaDF7UW8TXT_vRAl-AgkOww.png?r=0dd", // Mike Wheeler
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABWPrwEDquKC2q99DPSmcrR76jKmQIZSsOmRyWvZFPb7X71JxEVA4JctQvF9Kq73xil7t3YQBV-a_quGZqkhXGuZ5XyOlbzw8Aw.png?r=22f", // Lucas Sinclair
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABR6lZif4N87iFa5YceQadeZMBxuroZKPsMNb4KCOvIAaRKMsW9U2M3EJ-gUcRZFXfTYQQTQfU-qquFwFksUXsRc7TryLdQclzQ.png?r=e00", // Will Byers
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABdoRNCtKwjdz5nhieAmXY1oFsYd2QfoE8WtC1z5abVotx0J_EJmINz8aDeZgy-jQ65QMrFbHhtGFd9O9p6hz8faL8tc4w3flgg.png?r=a6c", // Max Mayfield
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABaDFUut690IdH8gsi6QdZsCp4Ork5BeE838S1Bp50jTZIRJKN_rNH7vZNEewhp4TYDss4HKh2m-IOPDsBXcyz7W7Rh3PJWZOjA.png?r=230", // Steve Harrington
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABepZdm_WgQjCeeNR6h8FXnmsFhmY_6kJ3VunSomdWhou7FNV1qZaAwePRcYHQBVvcW_j98RJeGmcVWogULaHeWE-uo6T7sY7Dw.png?r=74d", // Eddie Munson
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABQKdfWzyRyVPYbkNK6asgCmvHnAX0Vs9AC4KQseligIVZIkfS6sNvG-kvQFW0cE5v8KJusLWSftahSVEDpaWalsL02P8LrpCWw.png?r=abc"  // Jim Hopper
    )

    // 5. Squid Game
    val SQUID_GAME = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABZVGXhOsxoGsLi_AQossmiSk0r6ZFM67XITip-PJSl67vZ0oefYWkT2ibKLm1StKrekIAVGbInA14vtORI0IQJR5Jyn2Foaczw.png?r=a8d", // Gi-hun 456
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABeK7p_z_sAKIulEPJJkV73XT6PAjwQc6voxCgfNiY30oGnU3C_QNk4gdEaViqfH8w7LOq5Li9jcBMAQg4fcsu4kg2kTJQl3VCQ.png?r=924", // Masked Soldier
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABSHgplQ_aafX44bNvOZQPQcNAHkivN7X5ad35CkSOmjpHTrhc5hZDljV6roFjYnC58hVFvyROoouZSuUMHSwUxh-lZizlr66vg.png?r=3af", // Front Man
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABZ4bdyS2dqvIJudx7WUHKoZpVGwsmDV5Vgm1OeDvr97kvDmMxPRFygI_Nnk37x1H5M3UfiE8EnAiFC9e5XpalQ-12P5gPPNR_g.png?r=c3a", // Kang Sae-byeok
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABSNGu6ZKN_qW0D956FESEmshICU2EJpQpbBplKLzyqbObmoXrW9VMGzhQKVpUnUgg1ISV1gcNSWwF61pWkivEgGBbhRcZk8odA.png?r=5c1", // Cho Sang-woo
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABXruQ_GPKXrnaP44RC-KjkqmyPROXh0RMQxay9TLj7razhf6zNhfQSrDuh2VJkrtm2XKGRxu_pXGfjRIkZCd-_xTuB0t69G0GQ.png?r=8f9"  // Young-hee Doll
    )

    // 6. Wednesday
    val WEDNESDAY = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABdSudeCoep3WSwhSK4GNZ_2FqmcZRz0EYGzmG8e5ut1oct6vcg1147R10lxM5ptw-S40eMUT0vDV-be2KUnkzJP9DLP8lloeFw.png?r=94f", // Wednesday Addams
        "https://occ-0-33-37.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABY3XzBjNxugCUpgSz1TyNkzyXDn98wJlaUP8Bd7zkpYNsJFgoUF3j22XXMNbTDgKZ_1VCR6mjBw34Wp_mVRfnUvh2jh8gru3hg.png?r=ad4", // Enid Sinclair
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABa6aSDfuZFahunNAwVOPcGsfUdNyziZJXaChp4_s7fGA8tQwHjtYfI8-OialhPRtL956OPVG0cvirsmFqhn0hzXACtBKGhwXjQ.png?r=377", // Thing
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABZHj7UALVWQYKdT6Jjm2iR5z9jEwevBOS9eRq2S-AW_tkZldZYAEnaNZeNKUyDg4_6urq1U9BNZYApeiRMx55zSDJDPBafOMvQ.png?r=3be", // Morticia Addams
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABaWrgqgGJZq_3vRdmTPphgwoDOBYGLfSpDteWgI7EK71A1okZEMeDM2PebSfwutgqHSrJDmM5fR_vupmkWWsruQmO15i_lACgg.png?r=541", // Gomez Addams
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABWqLPOcR4y7ydud6G4p43HcmTA-bjCrsGg59lKaoVhlx1Mclf--Xab7Byfx1E1dv4TGck5vNVP_sd_W3T0iRed_IlKxL6vE8LA.png?r=eb5"  // Tyler Galpin
    )

    // 7. Money Heist (La Casa de Papel)
    val MONEY_HEIST = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABcu03upd4-h51iuJQodBOJ2cAXTIhwooP8dKbYhcFlNZWmhslNTIYhwNPldx1WD-8vs-XUzwJhnS0Jj4Gj7fTg7Yf6IqPqpxIg.png?r=bd1", // The Professor
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABY7qAajmbWmcjcTuKx1I4Y5qBhPCXooy22oRvjktPAPP4_ZESuJIckKPcrv9j_-Q-SlH7Gi7U27QQHvpplRmflR1wt1gJWajyg.png?r=3a4", // Tokyo
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABUguxzcBWX9x5YW2iE4Cmy7RE66LKJWfs7_QCRNtSlSCxtFTR2jQi9YR5w-51YbohOHP5QHsCu0Fdzw7UkEkMqPAD5KguHNMOQ.png?r=7f1", // Berlin
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABeeNGTPSmjaXoNjA1Hm6BLZTJeFuWFsS8KmBqNbS8CLK7xJA9VmWGgZNtry1GT307o-niK2QUNDKpYzMDPc6rm0Rqx-hQXrGkg.png?r=263", // Nairobi
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABW6wDIxxMWh2FXjIU2ULJIctLYfylWD7x55lGcfvV89EYuQV7gPgZ5Pa07f8njyERe9iVrfnvdOTKDKQDbYILnx7U9rChWkdVw.png?r=934", // Denver
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABe5mJaWpPjvYaqsg0XDZpYVpIoYtWl4puiAmJWLEhYnINGaBWL3t1CXaDOJXVnAEx1kfgnFVN0joneHDQqqo-7HrK7ZTaeeITw.png?r=bd3", // Rio
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABXhG6tI-Xkq9edsK74Dror8LCcA7Qc_5dAOcGr0JfJFaomYvKC_M2LPC6ogdVYI1VUeeBbZiQM1SGB_cBTF-tcAyBUN_JGBEhw.png?r=7e8"  // Dali Mask
    )

    // 8. One Piece
    val ONE_PIECE = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABeie4npL1TQwyzGrjePaDtBwa8K0t4d0wlKezKYfXjUwHIhry0eZFwRqQchAjeagKjcd5FtE2z8idkyNQilrnxPCVhEfA34QWg.png?r=c42", // Monkey D. Luffy
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTBFsQ2EagzwrzGDmdvSDD7nmfWJSXhoavkHv0gq4V5yCkk_FL2eNG1ss6LykQYOq4-8RpWQpVPHlAnhtXKlHr2HQoFd21W6-w.png?r=955", // Roronoa Zoro
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABS3BFf6F1iep7G4KuEWs4jYsKLPWvT2v5aHBntZyoZqK6XZDTnouxzH27dg6zZvvXJlNRcyLBog7gwpNWSoBfkAz9-RFNRopFg.png?r=321", // Nami
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABUhjP3-tij2B6zDSwxOw2hkGygAp8WjokRSWy01aFyrF6xuwwdnYbPFIwEY-F1Xda_LQdubtyu1NrCvDWXOT3mDfgdaJU1Wsqw.png?r=81c", // Usopp
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABZuu6ejAbLeGUvntYG6pIS1ipvLSrk-XkvIUB07EcQ3krC-WdnuphhvmJYB6DghedV66JMrwqB9wKLCkZsiK_gKMBM2FLFGG9Q.png?r=ffc", // Sanji
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTpyNg1QfvqAIgYguOhPG0cRU6lxsycIkZuS_AuR2W2MexhWJ-vSw02aoqMG2BXQPQTwS5oMF0l5bSVS_TAvfKdC59srxV5wTA.png?r=4a6"  // Buggy the Clown
    )

    // 9. The Witcher
    val THE_WITCHER = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABXan2ftdVhTtVVzZctW_PhUGLw-uzzdXn1BaTkNyVzJQk62yuNZpVU0_GUBJu9X6ry23mg6k7-C11lblVvDFot41ZZ6dxcZoFw.png?r=a4b", // Geralt of Rivia
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTfUlmnFRKf_OEUhru2aqso39FKxONTd5Dt_sWnNj5wAg4bbMBZ8sgZupTfnB9IQ8tmWcrzRiyZsCp1bLKb_n7VrnTw3_Ovw7Q.png?r=bd7", // Yennefer
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABbBdWEzsqtpcYRfW0oClQ8jdJx6uHK5oNiHQPNZrUhrT5-2gizvuV0zRpgYoXI-hS7JqdZ1Q_mCWUUWlaNx4pHv1c__GSpT8Gg.png?r=cad", // Ciri
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTtzfQn_TnlmI0dXn5jkfRFxmK1cjkW0zvz_qkvE4MT05lZLOhPuyHXGLF4EaOKu7aYlkrYf3X_a_af3ubt2_hek8y0rYcVBbw.png?r=181", // Jaskier
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABakRc13qnznu9gXCjeNTetIpWLBYv1BHtjenkcA2UPHsk_oKNyiEjMqDg5JrLMa6B-Ynairtq2_fSFPjKJ6mqB2xuIeeCZm23A.png?r=e6e"  // Vesemir
    )

    // 10. Arcane: League of Legends
    val ARCANE = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABUkvOCLpzPdTWJZAa0ntjneGlRbwVD_29llzq6-Iw2HgXHqWEVDuxs9EYQhtIjze6g5QNjxKyLtVgWosZ7b-b5zo6Q4sRNN_rg.png?r=59d", // Jinx
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABYQm8gxRTmSri9n9-XbbfDZFfaKL5cuJk6qCjQ_yYs72QVu2hLI-ZbEwI3zQiNOHT0MUdhFnvJkt3v8HBxOPg086xIDsMxyAtw.png?r=d47", // Vi
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABVe7TgSyaY711GsMBuILmT5OnGkxSxE4WT13On1uT0a_yYTsRY7VyG-Kd2j2pGeFp4mTaFYKXmxazizqjMGpu4Hj76sjePAipw.png?r=ae9", // Caitlyn
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABbLEEun9sRrpMu7Ubarx34UxX2F8e5pLgdmQ8Z9Ah9BMMgdy9Q_5NxVa--nuZ9pWmepc98T6ww7H5UsjC3dFDTUD7YAnOgoArA.png?r=a16", // Ekko
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABelkMs-h2DXUYbzHHCaFQo7ykBvO6JoCssR5azSK1jNcUTRExSzh9R1HNbNbWzIhTri5iN8U3N9GSmbXeLASZqL5IKRHLri1PA.png?r=1d4", // Jayce
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABRp8SIY8MrmT39ab3mMLfmQJXbqadfYfBgxgiHOicZRcQiKKGzofuF30ttGIfs16m6IJZESxa4-o-LToGAhonn69QkSRrfh1mg.png?r=558"  // Silco
    )

    // 11. Bridgerton
    val BRIDGERTON = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABZYoflVXvpbUIpSWMQS9FHelYN9Ng7ZfpmQpnQ6MkleFgoXcmq_TJVuzN78OrfyOyHiNsvrrlwUdVnOUM9JQrJ6f6Zy-z2UpMg.png?r=bb0", // Daphne
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABYPTk-lJwL8jeVZoJJUHDBCBKTOHvq52XpkgHAXGAI9tzk5eUGy373181Xuqtz727B_VDlO-MyIgxFGKFDKYryKJgLCfi9dWaQ.png?r=ab6", // Anthony
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABdgFndjYdBrqj3q386A40uOa6308UTPkyNQjDFJ-aUQQrk7hqmLD7jotC59fAwnOs78kLMGBxLWMRevfcsnOjU1d_kvoZC6Fdg.png?r=15e", // Simon Basset
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABXh10ggeTTdhZO1JIH_SNQ4gp0vsNnWfE8Mg2ckwzGvUzJMRpPFCujRK3Ex5K9VbkIyvUHQ92LBVdsemkj6zlpquL-qWMCNKeg.png?r=229", // Penelope Featherington
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABUl4-6pOPmMxpJKIQW5t8u31-I241_MQ9WqVlC4vQslMYQfKyuENj4MGN9B6FBWTbv0LjoPGz6trUZYS7bCMUX8i3W3cra1DRQ.png?r=33f"  // Queen Charlotte
    )

    // 12. Cyberpunk: Edgerunners
    val CYBERPUNK = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABV61qd0jRws8pUHFXNQZl9EwaEGJSzJnkxHWEzeN3USjSdaElFZPi4cMSbQwsu98cbbFMYK8CobZFEs9NEgeHrWoclmlYVrwnQ.png?r=c3d", // David Martinez
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABSfWYidQLOsDLtySYP0VuMvfagzS2r-Z0Km1KnwA9Z9qwglvKN4UEA-FTqodT-68DHIbN7vbpTPqkX9qJNQavDEcvvm6t8ysaQ.png?r=a38", // Lucy
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABcgMLVpA1ziToJ-jDf6waN0CJLwnYT8g0ykTBTeKhsaxhMZQpJr42qwZw9pjZ5jg6_rep7Rmhq7pJYll3t8K3u1lxfOX9_ZZNw.png?r=a94", // Rebecca
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABUFFcKla0LZjZ-y0oOxN9jUSo3mJ6V4Uf1Pm4M6cwMQsu4B2sEsgQPH97vPCFy4Z_zNSpCVa1vvOIveJK-v1zJ-cghcotTaoBA.png?r=844", // Maine
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABYs18PrrfjhyZR9pihCER1357gWKwOXiGDPOV8Em1fRvQNFK52lB8DLunskmkqCcYhEWY146G-WRAAZAt-eNpTSchHXCwC9Mvg.png?r=258"  // Kiwi
    )

    // 13. Kids & Animation
    val KIDS_ANIMATION = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABYZLnJ12XUDAlFPr7QustN4andtAJoH5K7zMKpz9wGRWcjE1RQ03eCaac8n3RYoYlvEcbVHci8xKSm_07hGwM9Jep1o9B9cgng.png?r=fdf", // Boss Baby
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABZILzGOa2m6fWIlncipKh73_QaDnQ9XUEmqiy7VnQRP_pt5FbYZtZX85pX3Jc8iDxnT1wgCFo-3Fmj8P37_wTMK7HeytUoQjUw.png?r=502", // Carmen Sandiego
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABdirDJXQvg_ibQIOHpgQG-mq7358-cbvzqAA-Eej8TMfPC9y3is3xhzoFKdK6OKVO10QTZacYgviiMGWkMiChTdXpSaetk88mg.png?r=6d5", // Hilda
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABbphDMErQOrG7kJt8DbEge3HkyhX38NIERMnDnuWGxB7kKtkX_99YnO5h1stYhsam7AtAkHjQZ-x9_WNdq3xDMv-1FPD87N8Ww.png?r=f26", // Cuphead
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABQCl5At20I7R0QTvVdZIE1B09n6Ir09Sw2kkD23zrm3iSSui24avuZ34zgJ6ZTHQ0CFEkLS77sQ_gJeXnNAh1XF7TH2nS2J4JA.png?r=751", // BoJack Horseman
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABU4kZHg6g6jeyHVQPJP4hgIDevebDCpWJI5KZf242InLHLOUviK5aB1fsKks3-FnMxAIMUrbA7grAUU96cPhb90ytsYCXggvIg.png?r=d48", // Klaus
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABWJTugBIKI6XqzBJTtniPoW3zv0_e8c7fiulfIk5h-CJ_ievAQ8FBIiKiEwCJ3akxzZZoVgVcAsSX_lcxEbnt7w3V6L9o848cQ.png?r=ae0", // Over the Moon
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABU0sGLkdDf78mPbwlyBdRdeXdNucvwgFkdzN9wY9-psVzB1OdqLrmh0CmiD05HH_Q5_leORCE2cFyjoSWFt0zk_ifdZQZquqwQ.png?r=d59"  // Sea Beast
    )

    // 14. Heartstopper & Drama
    val DRAMA_ROMANCE = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABRkRJHSCaKv8Y8Ctkmu2NhzLOHgVhI0xNTj0VZuoxWIFRAVJCmaYMpiuzb7hlINAzlwLtYAkHfas4HWoK4IMAs4z89sYzY_FhA.png?r=a83", // Charlie Spring
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABWU61tZQR8xwTc1slSJOFXiqNjGcP3c1NgQRwtKVEtYioK_khdg8u4WAL6YlhvuNNhf-a_hK9S3Y7Tq4LxdRBDo260UeX6G3pQ.png?r=610", // Nick Nelson
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABTLZj0uwbm7xUAiqmyK1hsMyiQWZLhaMM7yg0YhTO7EJKaCKA_g-xAIWwWzZKNKHLbCx6RupWw9u7enfPOveA7UKo1HPzxaZKQ.png?r=421", // Emily in Paris
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABQJZ8ma4sAR9THPLaaT0Ymm3-Vq1aQSGNu9B891G9BRBUYRPFY8vbLUcp_zRxUyNdDMTdLgqkwmWZk0aIxdIlxoaD6MuaYkgmg.png?r=fff", // The Crown
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABSXPwEmF1ALowNekM3-Uwn3C6sxfRrMDcs4b8KnmNQXc23gv5kjjYSnQEZvKtRN7RvnXVk-ynPqT_LTIeKqiBUOc-v06PRlbVQ.png?r=6c2", // Queen's Gambit
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABS_fCAimFcSuIgnyvchWA3eNUjEsCsciKhhXfklGV3idvRbG7qu7YwPOCIyNrZNjkteloppY2M-9rXnuvUYXPqTIXh5GB9Ri_Q.png?r=02d"  // Sex Education
    )

    // 15. Action & Mystery
    val ACTION_MYSTERY = listOf(
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABbidUu_tkyyAjZStpacpzss9f9-AeQCA9AJ7DRNHWU8gAsyINSmH78P3ZUWPJi9p70BxmVlYGHIWmtro2yHtounkoayo2l3_vg.png?r=70b", // Lupin
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABag9yO_LX58Nm1us8DLTWWH5pkGvxnSCHF9fPN4AIxeSWun5c2Yfbpkitqo7uTMoTX-Q_B5UIUL3q-K9NTew2c5n0U87341Kzg.png?r=ba2", // Outer Banks
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABdc-LIvGKdbMtfKA95Gbi6YDtndplqo9KxxHOvt-Czr9LRQMLEZqqAsevLJPEfdy15Kga8gKHs78a59cYUWAPck326BCnjpZsg.png?r=59c", // Lucifer
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABUlXX9WUam7Ef0DiErx18Bk9XRKM7COFP6YZKAew1K1pwflO2FxEBA_VVNHBnrdV7OdBHe7FQkqMEOQTrWnil0DjqQtzYlaKUw.png?r=9ce", // Ozark
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABWVoYzlSivjXDh16yJtaZ2BJ11T4Tnjuu2ODGqzGHaMvGliRQgaQrroMgVMDfXtlp9QKPYSKIWHIGjU83kcCpksI43rFWcus8g.png?r=83b", // Peaky Blinders
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABe-qZC5DfHagoXByYzYrxCjJ0B9cJLiq4YZKkZJBp1Zlb8iBX9ZZDQKgticWE5uhdY8DBOVxKwK3reAndYbQufT33JK1X-14vQ.png?r=2fb", // Black Mirror
        "https://occ-0-8782-2219.1.nflxso.net/dnm/api/v6/vN7bi_My87NPKvsBoib006Llxzg/AAAABUhFdklWISFDZ6SlJGKLQFBHNWycgc11SaPkr5SpvF8mqEJVzP2WirwqgtK9ztF9C4AGj0cfeGk4Ca-zSApr6UTGmEZpTfRahg.png?r=f8a"  // Dark
    )

    // The remote image library does not follow the legacy constant names above.
    // Keep those constants and the flat ICONS ordering stable for existing
    // profiles, but present artwork under labels that match the actual images.
    val CATEGORIES = listOf(
        ProfileIconCategory("featured", "Featured Characters", CLASSICS + HISTORY[1] + COBRA_KAI.last()),
        ProfileIconCategory("classic_icons", "Classic Icons", THE_WITCHER + ARCANE + BRIDGERTON),
        ProfileIconCategory("wednesday", "Wednesday", MONEY_HEIST + ONE_PIECE + DRAMA_ROMANCE.filterIndexed { index, _ -> index != 1 }),
        ProfileIconCategory("gabbys_dollhouse", "Gabby's Dollhouse", HISTORY.drop(2) + COBRA_KAI.dropLast(1)),
        ProfileIconCategory("fantasy", "Fantasy & Mystery", STRANGER_THINGS),
        ProfileIconCategory("familiar", "Familiar Faces", SQUID_GAME),
        ProfileIconCategory("creatures", "People & Creatures", WEDNESDAY),
        ProfileIconCategory("series", "Series Characters", CYBERPUNK),
        ProfileIconCategory("stories", "Stories & Animation", KIDS_ANIMATION + DRAMA_ROMANCE[1]),
        ProfileIconCategory("tv_animation", "TV & Animation", ACTION_MYSTERY)
    )

    // Preserve the original deterministic index-to-avatar mapping for new
    // profiles, independent of the visual grouping used by the picker.
    val ICONS: List<String> = listOf(
        CLASSICS, HISTORY, COBRA_KAI, STRANGER_THINGS, SQUID_GAME,
        WEDNESDAY, MONEY_HEIST, ONE_PIECE, THE_WITCHER, ARCANE,
        BRIDGERTON, CYBERPUNK, KIDS_ANIMATION, DRAMA_ROMANCE, ACTION_MYSTERY
    ).flatMap { it }.distinct()

    /**
     * Returns the icon URL at [index] in the flat [ICONS] list, or `null` if the
     * list is empty. The lookup is O(1) and deterministic — passing the same
     * `index` always returns the same URL, so adding a 6th profile does not
     * shuffle the icons of profiles 1..5.
     *
     * @param index any non-negative integer; will be wrapped with [Math.floorMod]
     *  so negative values still resolve to a valid icon.
     */
    fun iconAtOrNull(index: Int): String? {
        val list = ICONS
        if (list.isEmpty()) return null
        return list[Math.floorMod(index, list.size)]
    }

    /**
     * Convenience wrapper for [iconAtOrNull] that returns the first icon when
     * the list is empty (matching the pre-existing `ICONS.firstOrNull()` call
     * pattern used by `EditProfileScreen` and `ProfileSetupWalkthroughScreen`).
     */
    fun iconOrFirst(index: Int): String? = iconAtOrNull(index)
}
