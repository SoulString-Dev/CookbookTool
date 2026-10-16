package org.miu.cookbook.domain.search

/**
 * 汉字 → 拼音声母表，由 `scripts/gen_pinyin_table.py` 从 Unicode Unihan 生成，
 * 覆盖 U+4E00–U+9FFF 的 20992 个码位，每个码位两个字节：
 *
 * - 第 1 字节：最常见的声母，1..26 依次代表 a..z，0 表示无读音
 * - 第 2 字节：第二常见的声母，0 表示没有
 *
 * 之所以要第二声母：多音字很常见，而食物名里恰好踩得到——「番茄」的「茄」
 * 在《现代汉语词典》里 jiā 排在 qié 前面，只留一个声母就搜不出 fqcd。
 *
 * 二进制表 zlib 压缩后 Base64 存进源码，运行时解压一次（见 [table]）。
 */
internal object PinyinTable {

    const val START = 0x4E00

    const val SIZE = 20992

    private const val DATA =
        "eNqNXeuS5CivdBnKEBi7jSHCwPs/6FGm5Jr9/p2dmNmZ7mpfQOiSSklj8XMvZalLX+LyHFP+hr+nsm5jOZawOPk1l5Iufqcs" +
        "6zLkK17+7qqXf2f5+5A/h3ynyueT/B7y9Sl/S8u2hN0FJ9+f8mtdnuSXB9/zWb57jW0ZaXzOz8NPt3mMIT89+ZkoP53HuK7l" +
        "kutFfZYqn5dPHGeSrw150s574ukrnyotJ69V5emj/Madb/n+kKtV+V6QPwefpoSZg/w78+mT/ClPPrkC8yv3Gs/Op4lyJ3m+" +
        "ifcPHW/Xq7zhHN9UmnxnD15+OiasS+wHn0reRa6H/w9bs754h7V6jlueB0/4yFer/B1flRXZrsWVVdctrPJ1xyfO8tPYgSpX" +
        "nPIVvHHzUf51y5VDDGOVn5c3qNipiueWtz7ku9vijvEpaS7X+uANDnmaTb663PlZttUv2yWrIKs7useT4SnzHmQttg/feOP9" +
        "0rjkuxvuP7AacR+8S5XPuOn4hrhvlt94Ouwd/nYu6xHkz1P+vl6dOyESNF2FpKRw8RrjMxffYjjkT0gIVmvKuzzZc5ewA22d" +
        "LsrX25DPt+G3dYpcnfJEuDbk4OGfu3zmwhMGrH34ikwem6yFyMR3vRK+I3tcZP1S1zfoXNdd7hNipmTvMVAeID+TcvORfx/L" +
        "F/vUHN8s8Llkh1Kn1GW5Rhry1rPItRzfGfsb5U/XpsN64KSIVPrGO662Ko2S4eTqRaQqyE/LulfIKlar9Ez5S6P0uuAEYj/y" +
        "sh0XZKqW2mOveEYvz+L4VJm/Ks/Exifo8pMJUtRkP1d5n6HyuF7RuYk1h5S6meXembKGU4Fnq1wFj/WS7+M6TfZhJKygnLqc" +
        "uO+pbPIzu1wFslvkzTJPcKb0Rkr3iveS7zh+aqgkyL8HVwZrOZ18r6fyUJPcC07WxVPb/PP5Lj1BundezXEF+4i8kiuD57fz" +
        "O9uybiI37uBbQR6hp/Cef/JT+LnJnXi+OAl7qCJtlWs1eBbxTEmkcb3kvW395FlTKnq+VsjELTJJPbJh9TqeqWHNuPeuYhWH" +
        "w76vVxX5cZMap3C1eJWI3eW+9YoTfq2J+3ZADpaWB9/T7oyT5661c3cgnWVx3s2HuxqxGsm7NGQ3nJ7XL3TIbBNXDrwKNNHB" +
        "VUoFJx479uEbcFcq7oc33fl8vmJnsZqrfAp7IffD2gzs7yE7c3L3nEkbzt3wK89rNY3pSuYeZlqQu9cWe6ZkyLl1gyv9vR/u" +
        "oOzT7vi+OFueu3w8jefC399T3sVVaoK6LAusDvTRwbO3UHNHeUp9KmjClasEmyA6PTauwcqVFB3jNr51pXwk7gi05XmrvbtP" +
        "7tGctCvQAQd/XnXqsDU6qWVh7/CJNPGvHSeTn4RGxl23az0j7xDlu3jni8+tu16wGw4nrEGTc+3wLiV52oSg55L6x/E+jm+s" +
        "kgL94Wlh9Vfiyd34Ofwbd9p5X6yoWNAdT96Wz4Pn8dSFonn561ZrsxauyB4L9+zh52QVw8N7qm6ZtMMbz62nfsDXLmoRsehr" +
        "5hlx9ANwZzlj3M+vnvlenSs7vwtZ3vkODdf3mZIg+rXsXAX5JNfAUb/ijXBP1U8P7+dFyOrwE3der8F1rnxq3x6uf+WdxMf5" +
        "OJ5fORMpU5NeqzyFC9yFSMmlR0IvAF+nP8Lzr3KC1U9T3zabVQ/U8gd/chfbifWc7qQWVC9Dv5ftE7BTnZJCmd3roiuxytcc" +
        "r6py1HmFrF/POF+UmQM7+ByQwWdAtx6rvgs+LfopFdqrRGmAzva8W6fNyDyFeOo6BzVHwLXSwzMHfe8o3bAVbQT5/5cyFLhD" +
        "+H6k1XJ144pMvpH8fiJt8EbLOt1OqzO4t9D9YVd75LmSuH75E0sN27ycrfxV/vRJfVNpM6EzO/+GP9cLehTe0yGfkqs4eA/r" +
        "tYdVlOZse6Fk9k6p6OZZfUbh6mPFdX3yDkslayG6rSRING0uLNjnGd9P487Gj+4CNI2fkxaowEeprh5iEbOD7oYn5tK2qB/U" +
        "eTJEo3RPuU9yYgOvgDXP5XfFQ3yM4xb9W9SbgMWK8KMdfbcPPWm5Qhi09JWSscnZrFlWR96oelifnnTPH97x5gkLewqQXN92" +
        "7syssF97aMPFyneAlyfOgpOv8N6qNUU6hshYCfRpZ4oBUuNGo/eMcwer2iPe8COf/MPe9vdMDlmD9cL7iMd6iba7fNuub5QV" +
        "Gnus9NpnGfUZT3OHaCwHX8ff2Nv494yQcK28fD+iRx//OYrb5dymjWfSDfjrjbvo2x/Pw6CUwR//cg0ukeGTp+xe1GZCjprr" +
        "Dh76JfIoemY+R4/xI/eHz+m6PBn8dD/KnmbssN1+XtRhG60XYo/YsA8bryk+/inyHODHYO1OWv9OO/eVX9Dd3Y+eKXPQwCOl" +
        "Pu+w1w7ZjTv0cKyymun7oZV7NGb68PxCz/To3SPGavI9rtAhRXEv9BFg58fJE7HT9sECHXxWtSqwUX9fJ85gls9N6n0993J+" +
        "qLFWrlKlf0Q/eNQR9sEVDfQjKv2Uz4Kopo+HFlQkvULnPqc+V+FvvGMMGv3lUXk/9TmxVtuaRuyFpxkez/eBrfucUyKk0w9G" +
        "RrV0vvVnmf6k/42d8zV+Rc7kPXpp82F8UafueKOlGs90K7zwKW/+xRnfrjw6V5H2o0BzqRSci3ry8pzJNY2SNDJz4oV/aCdU" +
        "J5+mreUUd8Q3OPmwf/AJ1D/5ew6utUR4cuc4uosDlnI6eUcHSfDzHN0iB7cXeVcvPht8QbmCe7hbkLDddDS0+izQJbKLvYpU" +
        "FPo4IpvY+1FpE2Qfo29yxXZjPUNiLIdoIPCcw2fXVY70jaL51ZFrcH4DdeaH6wdfDr6ELCl9l0q5L5M+kBddscDiQnt1s4NB" +
        "HHKNmf66ete9IhY8TDvAR2n01wd3/+CZOOjNyxXkFNJuzt18WYnoKQuiBbprEvWJRfs+socrnh8RgJvQgQ26Wvy4Tg0iu1Y9" +
        "V/+iJu+MMNQflNPp/8b0OA+yKzyPsl8feZoCWRbtnBDRXhueVc4YTxNiqYs7i9XJ4iHGv5tnkGevu5lmop53tKD0G2iZRIIR" +
        "A4+DK5ypQ0WvfI5f5HXQ58H1O7VMNymDpbiTN52auGO4xjn7B7GXMw8Anp2EyNS7snrn5FdOXlv0g0Xb4mOIZwmdLbpIziv0" +
        "/qA+HHbl8XxHYDTazA/HObp1X+mdNp596JDrp0kgt4W+GTzzknZ+jzG4/nKZn4wDz9O8fr3uF+WZyE0PEqLD+7r07In+ECGj" +
        "3uiM4MSLGyej8mSoUOdbHVsa9948NNTKqDxek7GFRFgZn96HN78sM6Lc1PO88NmSYJXVc/EzPNDRV0yM0T4jU2uJkaQu9U4R" +
        "qY07gNXXXx96ytyzlih5uiu6Sp4/EfhWnmsk67K/XxHNPGqqy7eui0rrbpHMThn5UB+PuCIq4IrGT6dHG5bPXLfNY+0To0n1" +
        "GsXbl1BbpQzeWSTC1i0uOL3Xk/cVT1m0QpnqpcMDa84zAvmIrYfm2zvWf9RpZ2j6okjZujMm2IlXiB9d8C7fca0iHSHTGgLR" +
        "QKTo48nzMPgcsiaPp95f+Ta+HU+S2Iky5yY/9eVbrlsSz01i8Cnydm2GCyqalyg30DFExnAuuvrJzTuuYDUMovkHkuwbkUV5" +
        "y2uljveU7WmYjPpdO+PLh9aPe1gvRiRYxz+RE1roVs0/OxhJYK2nn7y3X5xdEV61xjiOJ2DSEmXeEb6uyLOc1HW7FhHbtlHG" +
        "PGMAzxXztFrHs1GXePOaP4w5ykfu1S7GitjP8fTUaubaeGJ7Ii+500YOStRtViPzWR6+W+aJzvQC1Q9daQ9uro7YgMKIokz1" +
        "afh+6kUovqV2LwzoW8jlpIwVRrJyxdSpbfF5eATjOZ6qdpg+WOAaHeaJOCJ4jbGaeHbi4USRozraiq/J/7g3KXh7DqCcX0WI" +
        "mun2dCKCztvuKXdA/Urf4c1ug+detUWkXyYnefft0xk1tI8834cr5C2+30xSvTtv0fPtpJ/akuJQkZiPfG7c8uYPNYHG5vLv" +
        "gR2J6aF+8YyJ1afDam6IlJJo7ji4NhvtH956yE4/1KqesRIil8k7D/Wkx5erVWyVv7Q5G73G9ToM9Zu6f7xX6sCre5UncYl2" +
        "S/zchieqMxGXYIQqn8qHRqon8QhxrHnGUt/joD6ReI5otuOVawtP+UVcT8hEvxI/1z1PRoXlQxRyPA+0dnaGt568y7WehhKp" +
        "ZkS0iHeUTw+cvk/XKGosj/ldJcGX7Dy1OI2f75f7j9MF/OviVx/usMYOWM37aYpvFNwdGE7ktbxmLIJGBeI7nBuf/3PKGYnD" +
        "fCz9lHpu9HfwbBIx3C7xafF0Xnwr7vuJez7EGA7KhkhSn5ZH+AzF9enJPTj/tQGJz/R3sDP0V3FeR+I7wBbBowZyJ89TFWXB" +
        "5y+RVPHY990VW0N52oqnQDxUucqNWMpJr029lWsdO1YSORLVVKf6YIznp3jFcl7Ok3fQ0/4hIob7ITZWC6l4wGG+SzCc8dUK" +
        "9+gag3IF5CwS8U+Ug5XaDroh9k7PSdGpk7EINHainrxWedNTTAE9DEV5B/XsQft1ar7CfGVovPOW9yr0fXuJh2VkZs1mLdQm" +
        "HFMRsI0Yrr6FYuk4CSffYp6MRPpJqWJeItHPGxvtRhf7e6czyzOnwZU6582dmq7weT5LE3v67Lf5RPBRjm1SA09aGbFUQ7Nd" +
        "lc+zl26o57C4MJhd7+rf1Kg5oYDP3yMS44FfU7vjGw7+xlcZMXO9T8ZkB78y1OPD2W6DMYTuq6x4DotKrbz1PIFlXw93MnJl" +
        "I08hsiour9Tpnk+oCCOkFrmUba1DNHMRb6M9g776VE8Z0vi1GDfZ+1anecHMPdhlNaDz8ok9O66H50B9IsUignqcM1juzZm/" +
        "8QxokIuWazW/DjKKM4Znqx1agfgDtVrn2ftSR0En1Nlbo6x0Pk0y9IdYzOz02s+rmifwHTgROY5QiBXHTzWtUIgdrIzGRycy" +
        "lBBnbE51QeGfyXb0w5VbLTeViHMFi5gasoKaM+O/LnqUO09BpGf5oe/qaFU0Rj9MD2G3Vznf32U7dX901Qut40HJpJWq6h02" +
        "9dAXjTnVT0z8GZG1sfKpN8pRpvTdmrOl56+WQ3TfUH8iE4Pb6e026gXi0EPllBjKokiB+slpeJ6FYadCVlh8Ro0BsTJ7er+v" +
        "fge07PAbMx5Nsz8eq4dYbZ+UG7Nj8JMgX2UHzol32tM4h+UUYRd3XqVwfT1lAn7HjWj8k+n7lH3SinjLLWnW6fWJ5M3SznPg" +
        "6D2qxZ3u8+70VFS801embM2VHtsw7JcrULxmbUtIw04wcNVu2o6egTyTRJ88bR+u0jhv7qx8nudSLW5gXkTsT+ymQfdnWDZD" +
        "4w9PzEmvu8tquLpHx5VNvY83Rpn0VxWTxlkDHoyvjFqHyiviWz0lYp9mYv53HXVbKRsbcZCN+AkRDa56O+HBigU6RNNu2WnE" +
        "NokCDK4R1uYmwqtZPf2TcRaewhOPSQ+vJ3tYkJEOtLiRd0I+6flsYt1WnovAHG5jhNgUySgH7UdgXk9z5I7RXxSPC/uz0rIV" +
        "fhWfcJ7akacjfMRXAaMBb30GCd4jn1zW6nPRp5TrXvTKrsIcLyR37frTz/JGBRct3mH5j0Z0nDaH1+jEizvP185TjTOtcQdy" +
        "zx8Jv3azEJVyqacaftbGkwtfpnZFyzUTTttXPubzZ7Obekpr7+VgnA6OhdqTUnt6Y1nNfjb+XDOUuvEtEuPRSJu5E9MQG53h" +
        "YeGkzUWjU+CrtfFPegmD/hgsjUSBNdH+wd9Ps9EbV+zHUbNPvhtQdpw6nAOvueEka9hKWvWnoYf2QHu+q0zKd0V+i9i9MyyP" +
        "2f7A95a3qLFix6OdBEPVe+czyd8vXZuV+hn66Do0o+S2uTZ5imub1EX6mUz/cv4n09UV26AGfKgJDC80dgNWD1iU5trw/Wxs" +
        "Dzn9LXXkSDbqJNz9oe26GI1Dw1AjTc0GpqHclH4jL9l5WgbjOK9roDm50hBYLiHKIYZ/Rwso8RH3En75+/SPcU14wiBnVU5E" +
        "KcgryM+3qZggtNP+0GpHsT5F9nNrjAH++I6JMuvoMU5FsfeV/h04JKtcJ4bYRPc1yKgTKwB/GijQRuypU+/Jjq76PkT6aNmA" +
        "+GleR/W/V5Qcfs3dK6y1rHnYjXMhez2ixYkB+SVZIshh3Av3hznVMuhpZSKHndbhJOqwywriFJap3JCLKIx85ym8uzI0IKFD" +
        "LOtIyAQWBw9dZGTv1PHQOzEpaqrWu9N/HYqwit+254cWWP1otY+K1GleqXCvyEfAvldnqE6g7dX4VjlCO08iMuZeUd38SPSs" +
        "nswAHg2vCLson9IIA7ipa5oj+z71Vh9S5dJR+gv/Hyz27EAkWqSFu21PDoloG7D9slJiA594ME4nirutukYiJ75CH0Cnln2n" +
        "n6R2VXTpM+nDb1zXnYyk8dNwlVZdc7iKIliGl9HE3oedOKxcdU2/50pv6vPOgx6jo+f0ctH0l+jXE3r3QxxDM4+aZQfGU4lj" +
        "Zko27EjpsM1Yce/UTyomGZncB0cdrvb/otYulvHNlB7yGeboyifxTnMRwOUTUZKNkXtmVqLQoikyrxFRp86/7M1Peh3wR6Ln" +
        "uY2e902w6so3GoomV2qKg7hk5vp2kzOcns95MLMh3+8H3+A0H4EeGG3WZtF1XFT6Iv1AzVHGgDXJGrupXM4ZAnWIWL5CzR1V" +
        "uyAjXmZkTlXu6YDhTMNv95mMYyD+h+XNh3lLkdkvXMXzDEWez/rLLiI/jzylrEYaJi/bovID7Spenrv4iZt5ULW0fuxhPPsT" +
        "EU9JQBTKzoihkOm306qKdLp1Q7Ql8kT7K6txfcTruYBG1+9Hnje6CS2yPyst2nPEwMw4vICqlgIZoKpa3COfOutFD2lFPovI" +
        "aV6KYaIrtaTEbRd0/mgaIYhNKLIv4vA62l5Isshcd28ENxCByVsl7NHpZVVWvLNG/8pPaHPdgL1/zhRwj0lfxtPW7ibPupcr" +
        "vcBIXlPSHFjCNWrBeu7IfsrdI64xajqp7UXneHAYuYPpQ20fYqLWPO/m1ev19bHYSq3eM5HNZEaeshCNKwctBY8saJace4k9" +
        "OWkhknpA45argdEldmOnnS8NNsk1WfdrA6YDYttJVh1ZSnLFJyCXqVGBxP1VmSbJPNVHpPPayIoAdrGSH7I8+UNLIU8iZhh3" +
        "UXt2P9M9vZhsVu4ieCLilZzIFdG3Hk01wir65NEsftqn5gNOiawlSIZX0LpjjkLeoFCHzUl0CfpmPa6qzNKD6+Qrdr9qdoRM" +
        "kSkBjjyhG4yKY7+XizlF2DSgKljNypzdSJo/UKRhip7ocmqev73ImxGJwtPsSSNpeFHwvsVfEa9wxJJUz62U65X6qRifbFpM" +
        "COtQ5FS4oTxgSMd9IvdQydjwlKgsTmaVtQvEPTTv9x3OI3pofiYgV2uFjyB6fJ/nA1QHd+PufqkD//F4vpQPvZuiKo1esVw3" +
        "Oz7fSj0hfqOr3HtnT21sJNlCZQSKlpun2AzNYsj6SEx8Ivew7H/yBkebyESIh5FF0xbVCcF4mYN8VJw5+IF3DeAtiC1Szt8p" +
        "Vrj2WMK7AkDaxPKlnWy55sW6Q9tIRNUhu9sBhBcnbvanXWvhWY30bJI9axHTCfRGz636/HMADUEGqKo1SpFIhJxEiRL2Ah/s" +
        "w6f9Ussdhol7p9xAfOc+Nfp8YqKsw6OYaW/4bA+ZHk1l3v4WvXLx/rLaedJCJ9FW6tGoZ1NoWQuzNPAG7tPVRy34zNx5oI6Z" +
        "1jhXTzQ1/RhqszBDkDoRzEqbFJRxgDyYoeuiHbbnCLRSzLbK6vvY7eqJkQJi3g8jhETPnZoLGFUZ9BHkyknuDOYCNFLR6Ieo" +
        "ifi+H9qtaXzq8td/DEA5VR47lpsyl7u+MfXKpF+z8qwMnhfoB83QILOt2ZwnNPW85pgbf7YQ9/c8t4oAlpSpC/kMl0vIzlbZ" +
        "65EPZO4Quz7wNCB/xrmuRPvq/HkCw1j1m7E3d8XWgfr3xOyQt3wq/A1kboArBHJx/KL+8W6sbZyCNjXHKxI19H3B2NEYLyTd" +
        "CfxLrAkYZ04j3Y0cbH33devGGNNTKb7Kw0yGaFVl1NWGXINEhwM4q6OV/hDRnub5Nq4S/IIO72dPZk2UEzN+7NVhfn+0WEcZ" +
        "BdASimw89IUjo6GmiA6szeiGpsuu+mS8bWjOxxhxGqNhxQO8u6Q4+MGVkrXyxJPUeuWzf5aX+X7x7InfEx+eVD0vYp+NM6o8" +
        "iIN+r2ZJo6GU3TXjGTZjgkTKt8bpjfmkaqgZuN2KqSVFJ4mzHIpNrdT2hgmQJWH8ZUUQNM4aYkDLL88fv3VRjYYnE98JeZPS" +
        "yegvlKxxj+d+gApvkMUdvvjJPVfka4q/GQ9iAOI7laS1GmAFZHqYB20xMRoxj/DgZ70XxXXk1yESWdKjsT5iYXiZyEbLMz5B" +
        "o1euwGMSQu7wd61teiI8zvhKYl1mITKB1Tzqs2RjOGPV4dnG/fUBWLFB9qdYcs99OFFhwIwGcehkXF/Rr9ezf/D29J5Fcojd" +
        "+4kIivH5+eX+Brk9cjuRlmsaQpEMT940PzF3sSlyBOqkHBPLSOt2GPpXqcUkeqYGOp0yTDbLjym3XGKbUogFHHYnkVE5MYG5" +
        "nFPUMXhGyFxDGwZFmhiNr5ufO6VvZWRS4UU4WYFNT4FLmnnKxp6DrlmNB+uVuz80QyPxeSm/agSNj6sD5gSefk/4c6V+Y4Qz" +
        "gAlNQ6I3omEvZ1bWCxEGnj+rdlUmAHIDT9jjTYaSeEMNLIbJSo2qUWRHZA4ZGMdcFsvuaRWGcktYIRSTMY0q8ZY2iCtdyqJs" +
        "hgdmajXGNbGSGdIsP5Wpy0RbpuagJWS3DKWcfMdAhPqhL/teLe5VQgzmSYmnZ1ZFAZfSDCMyx5U4gPrPK+W72XlC3k7tLHep" +
        "FqKYRfRvgGcqFgU2H9pjLs4YX5dh8clY5g8xy42WuBgqhpMpZ6s6n+WAi4ar+6bML8gibEx1hXiCIijwkR151K7CngNDF4n/" +
        "JkqUYr4X5dIZVv+iGN7wuUk9udGzrYZzegcJeOLzRgnABqZqc7CplU0CdhzrWch8u3/VZFH5xVb1IiuWLHoin5KI4C+3WF0f" +
        "aqHkKUey9wFuodw7sKHlWklrTaL5NsobxFnKzOlpFqkSH1E8bbptffmlLyectu+CZVMeXTItHpjJLxZ/dss3qEXKlD/FxrXi" +
        "SPklYQ97ZT4L6IafJ+8v9lfUi9iVToaunJbA2LcYyrMn1Tgrr9c9uL+xKnOLuTbRWvCWHfw7F5e38unsw/JVyEwfTypeYhWc" +
        "c1iD7DozphufRnGdV6+7pMytJqcq+5Rp3f/k/cWDOY/Rm7EuydgLyXxR5aN/oT2JHTiud3x2y55VanhPqTkYIdwndyZ1RvwS" +
        "S3jVQZDwxpoeh3AGLOUWmRHtjJGIDTjUdhRmWItFCMHQpsmzo6znbmyeKl4iokhFyKJq10VrDubLmUY8TgudyDq/Vq2HhKaB" +
        "3wVOQ+IpeDPC9CsqLFFzxJ7ocUetjDpOZcaDCXtrHkusiScXatnthAXaMfEAQjFbrr5Kdjh5fxavseqSWe1qlqeYlqWvWRIj" +
        "qxjOnydvlZLko6PGDL/6rZ60yr34hnImSyi2f4ppZfr0b/UH2WiLXjUQF9PIXyLM+Md42km0t3+Yado7M9A7eUvY7c8Xmhqf" +
        "KmPve9klqgEjJWit1nhlRrTaQAwLvnnrkScVJxwMZ3mr7BgDKddxWqXSQy2qlWaFfqWzqgLjBuOJd//9mud/PPQXHvdqq1aJ" +
        "72mVFvzy8kMSRY+J2eL9Dr9oPuPNJCiapycz7s74garrvdWi/tlPlB2WoYAzMLpV4QTLLv7t6glKHHIEPt1hLOVBG7YuylFX" +
        "DezoQya+n+aQv8R2E78rEnlW5qjI5y+r8ejkz0Hfek/g+4q1krvkRlTwsgpNrVphdY84FgeviLNw8mu9wtaMGyjKxthM1umH" +
        "5H6IUxZbg7yUXzWFWuRhaMt5NLGZ8gziWhVy1BWn9HyzOURrijb1YNjg7qh9WC6HEwG2QaffSFy5PVz/VqfoRGSsw0Dkm5a7" +
        "KYO3bIlSIZ76geirExk5Rwn4+1s9St9kl6jMOa63n8Td6fmjMvWgnFxtNTRQ8yPBuDYbT2U2K0/PX2IpeEHN45fYG1nH4bZF" +
        "MzeIDwO5HKwAGkmCjzKGT7Qf1ypXWOmL7SJ5n2C25ciVLAInXgEYwoioMiWiZOWHoNqgMlZPfWOM0gyLDVqBW+nbXRqz4evX" +
        "TBZZqf/GmlWHSgZU6H41GunF0FCNu1IkV3Ygy3Ismr0vWovBMyyndeC92gBb60vf6eLJgt6MiCDCE0q8jicWq71OZHYlkz+s" +
        "o0ioRZWxn8aoKFo73mNYNX/kNcusOUHYuszaX/A8AveCexKVv6x+Dip/IdH1hMR3sWvN7ZTuyPqg6s+7ESftooGgK5oEUa6w" +
        "aoGrSx6Z2UaJO8Mwub3t+beLUWtTfok8Zd3PZygDNFI7aV1sU881qW+kshjkuYesr9gyf/EswE9T7tI5mYusWm+3LXq+/Rjd" +
        "tHGUs4pILAxWImNNTkTIkdUSzLDR5qRm2bdV0cDjXjarn1ZNpvpSeZRHvSz+rjx1yk/CHcvT+PSb1vf+PQFVd88QLeQdJQm5" +
        "IPHpIpjXyhYYOZbDuKewTYE1ntd6GNZ0MA9Dru9EpHmwXutalI+/2ZNA+mUfZ9w1y47T1qyaVaTyBP5DrkklkvWcluWeUWvU" +
        "NSp/+BxYlz1u5s0/IfTVWNIjNGMwZnIxJ+PtxtpCWJJ/DFpy+rbn0LrnXp6fp/dZyqQ36NTvrMx4Nq4keH2uO54bxyyW+DSw" +
        "dWUyY9wH46Ogvl/7od3BmDjTqtTHrw539NVy/ZaPcPSfS9WMETMyyoXceI1Ds2Brsjrsbsid5ilV0hQhHZbLwr782T0O6uba" +
        "YtOT4FqgfuVaVdbDtst8L1SPo7IGfnl1uTvTTM6Yh1WjdLLXu9WPX8g/l2v1XVGfi1b5CbCf8qyD/DNUhgRlYNLvmYpI1AZU" +
        "vg/kGEU22NVB+fOKfzTUp1ACrCZbdHT6sL46ai1NnZehOoqJRWPDKQMsmwd0VOwVWFndspiUogNoAXwBZSN/BjJhyJ96srng" +
        "8cP+apYxy/kNT9Jq1Qr/2P38Ea3ckvuc0CfHL95JZKvAkt1fVIKD8a7MdVitLPr/s4uOrw+j1rIoa3CjnCX5fL6msSyHYc+D" +
        "nTnU06puXd58NbIfzuo/O09ptEr5xNMoPqNo4fgQF6B92awaLFufjsz66M00vXYPuc80M3UYqg7l/6faT/WJN/rXmucQm+ay" +
        "nTHZt+h/UrOaN3oqw7h0w7n4vcjzxPdRBnC2GGDCizRmtnsxVfCDgvYbUHYe617aPq5NK3NLQB2WMjqDsmlpeRTf3ekDrFZx" +
        "N11nzT45zVszDkGzk+Apddk818i/t0X52sWQ9661Igu4mIrYNn/wPvIeAaekR61NjZ9gVWLJVjMuCz2im5EO9ZrIekvTagYu" +
        "Vsh3ysJtZw8s9J3P0K3+Efw2eEqPnYBBrwZZppU7Cf5IS0BgnyOyFvb5oEPCRj4YOD8F+b2p3p96IPQCBjP6QXnAjB8Y509l" +
        "ao/NWKVad71qRVFsrBc8+8kdS8aWxbpMdiS46fNiTZ6xWe21coen1TbCBqgGCNSl0AHfsbJmTHF/ekFdEe3T+AvkSRdwQm/r" +
        "apPJrWTE9El2HzkhbnDNZbcey82dkMl4/9ErOBj9KH7z7m3XvDwqqLr2FSg9vayCsq21Z8OE66J+IyK3g2wbxZhlFdz4DOXL" +
        "ZEU0u0WBsoe7stcUsYvMQAGF6Gu0+jPRRDVSv9w8XavVknyIWtX8WgLYlW48U2f4DNGbFowr336RoTPO58Xf6KHzsT4oie+w" +
        "KtZNP/V7XMWxqtbbub8Y4zD6MgxUPAuJtxD9vMyHYf1VRINmtUCZ+78ZR4T1F0WRXdkzF7SHySyW2WmG72v9iuKByBTAH395" +
        "M8q726gHEnGPTi2cTOqO7BfV3t3QUo3MEBs9y34rk2SlV6VcsU8KcVpkj8p1Zb1E8tF2ZNFZt33w/WVfS+0XKwjIkqB3Oiwb" +
        "ksjv2UNntjgO8YYPZCLZyabezMFmq3LczUegjzQ7M9HggIpsxdW+l82yKZsR3IfTuGi5a+5xEK0l9p6I2zyHWVF992iZ3O0n" +
        "bcBywGtEhbj47jFZ7eQwBL+y2iX+/Bf/Ue5H0X4utOhvJsURE4YnvFokpzpMvPOpcUp/uYRd+1rsEZyTxn4JyqI/74PdS0JS" +
        "bdvrcYruK3Iivt328Riz56poE7gEwRA2zc84q7pjPEschu97kDG3qR8tzzvgp6RxkCmo1vTtkPUJ2kVJuX7O+PQXfcqbfoi8" +
        "UFIJy4bEJ+raZKz21/oM0x9qt+WJdlzxa1+NhnmtXCXlbSLLjnrbxzhJB1EL1UCouyh2DrpZ6f47+ZXaB/jttU4ys4d15JDn" +
        "fIDRkFNwOauFZ3UnMRjtVTMorZln+7Ls6LYoYiYruw1DdJmzZO3VQUuslkm7Bbmh6JW8Cyuu7OyYz6e87sEM4DTL0LjLiv81" +
        "IqmK6CH3CB7lrt08jEtrnZlmMd/usK5T4Lgw62s1CMWiPtRbRuLc7LQxNSOceHVF4bRjRiNOrXiv2q28raxH0NzWJMqpCLy3" +
        "PmXa9akxp51/HAd6UqKDy6MMhDiCslyvGGa8THM/Yvl7LM/Ks+zJ6lzoDURe4SJapV78wogAuMulspYW84ZUo/1hFYD1mifu" +
        "DSMJ0fN9msam1vnLmQS/seN2Db+WjX0SNNM+h2c+zbfLupehJviw7Hq3SsyBDlWW00vU0NvalsNYy7IT22lnw3XKTdKuS54c" +
        "BOSnrg08L1n1oV7ysN4jzqrUxKMZ6VfnkIxd3+yUOctTXkPrPuW5/Thhbfy52hkM480xiXYZ2TLCrF8Bn2g4057TGOoXq6/2" +
        "LyIg7Q8Dvlczi5hCtc5VrBfkahUix4OZSVkp4yeCm3ZYl6ZGu3fxKbbr5RY8l56H2FhPck2uJOxiPhx9tw+fbtVYc/nujz39" +
        "tLogjZ+iagHvrEL80UxMHz8ugts7z7as5SS/N2flycgPeeqWabFW1qsjX5CUFep/fa6GccozeyG9+gfxUWSPMvalec4fPnct" +
        "byeZvHrDoZN1YEmGWmrVkzecoBjnPdunV+uo4skPW4+1pD75k57eeny0rolICm1ikFP9ZfwgOi1qjPyxrgiKmUWrFInWOUGe" +
        "YNV/XUswnK2YXghvzbsD587/VoEn0F3mZw/qz7h//lQe4mc8u3XiuehTVKf64nirnvarB+ouMn1ZB81cvNPOVUA+uRa9M5fd" +
        "r2vbyEUTLd7l1Mbj7UxFhtRuFcLDeino2YEMyNfYseowjoXmurw7DYVHjcm6HSKTNfLkTk8EFZ7RbhadXP1HO2vZNVItkXXA" +
        "jlnIr1Up7paPa+RZZKs+HMbNyayBFamKyvc/WNcg3xdR01540a4Tmc9Sy6cxYUMvgCjW5lG2AVli9FtQu+SaZitQi0g2oeWr" +
        "vLFLnekrYLGauborqjyL5Y/f2nJU/2gVBvg9EuP3QV9Gq+Im2bs8N2TXROq0i7u6h2QMHM2/hQLev7NehbCRottSNa7M6NVi" +
        "yWH8GUemTTKUr4qsFaLLWMfpdwlnO/uDeeuiluhnorouds28M3O+fSnb0+7gzA6w61h6O5/tubGTyGQVsHYg0xzRxTN7sUvV" +
        "+lUs6GOZW+JuXHWcjD2E+LX+bVFihpAl5thlxfOlWfZgucW302PppRBfeeNl9MIa7M/4fLMb4TAG+m5ZIuXHaZ8p7Woh66Dn" +
        "0qkHQCQZ2Z0ZGQVVWhTNWTxW9y/SHDRKYL+hiv4jost3xVe09n5+PL0V5CUvsvka7U0x/09xzrCcs/YvZam2Blu+i8+4q+Q0" +
        "4BklF/nKZ3Btd0P8Ait9y/Ipl/Xec9SBmaz1A/01uX9vLjv/PBK17GJ9d/Hlavl4s3qdn/fU8CJFBYyFbJq1RG99RViBvHzL" +
        "h5yKQU9NPYphHtwe/mUIN82e/jLI2htW40Pgkdm8NvQDbcZokpWZBT2WwL91t0Uuc9H672NFhkcxV1bJOmJ3qKs41Wd9Fs1C" +
        "q5+cZuDfa0bnVu005zUjRARn/iyIV/4aKmZMmrgOxHQ066GdM/Sk/LfyE7H5MZrtKu1Q0b646r+J5ju99aZSxsetiJtTWW6W" +
        "ySJT+gQzpvPcKOtyI/Nut1hf47Vj1R6nWtU506Xo4OKu06KnaX0EyWCejh2cxA8NwyIK8ASHm9SVqNMJ2rWQzMadForSWlH9" +
        "NcyHFs/0nLSfqSjexbqIuGbXkBVlzokIxUrdwHq7Rfu2qc7oaEkgzyM7s6o0poLar0A5uBllH8ybA7POqVo1U2FcqB0lib7e" +
        "7F+ytqkscEV1bsbHF2vRJMJZtQ4EvvYta4Qcdk/P0N5BWsGi13djsoK4/MuGJJzhuSfUAMA3f6AnNEOcplYUOp456II97Ns0" +
        "9Fo7kwIrorfimYEow3x/ckRzppTipycq9tzbX4b9Yfdikiia5tE692J4brR+cIfycrAr52q+xGp1IrIR8oTe3q0YhvL6hsin" +
        "pqA9WqfPCdi6s+6VjyEeq/2kdQW5B/Nq8r4J+Euy3K2cga4s6LcXKaPJiifEGdKOad46fW5kGyX+WakxrBuf8a0UQRraj1aZ" +
        "zsheoiM1+nLQ2mknQ+aFdV+t1pRZDLDNfxkL1g+P/qs7mOapN3YBUOYLu+Wxo5fmO2CdpuXX5US7QHzNi/ZwERa6zml6gr4v" +
        "e90GWi1iO/K58TiriS2MJMggPxPfSBkFF2I9MAmhR5wzSwKGWDK+qaydn8bs1R1XJliwvqGja6cwx5UE2wX9j7W+LgfdafH4" +
        "ozOcsbhsNg3MILFs5FrBX0EFy7VqJavmRYJ1b9kZ/+D8UXsllzLP11shoZ0itIsD2P3eOtEd1sUTewRW+PwPNnHxd7C8kuJ/" +
        "8p09WH/LaZmC3aIBVA+05TxcU7RFubzbqlebFtUrC+vltWTjGSjPerVTVC27oZobTDrlgpzGlVUJ1qti91FzsaebOL6z3p3t" +
        "7XXE00Su+zmtk0g25JwcWvDMRjau5cYKwPjDU9yveyojgapvzdrx+OIDEsGfxLBVb1vtWbc+EIGxUEPlldWqHdQI03zqaixn" +
        "7TK6/rhM2iFbc7knuVRHZbyZWGfoh6GToru9WIi/SOTlod49eZ9Te+SASQb853i7Uwzjm1fujXvtzbLZiiLjPO0NwAZY2UeQ" +
        "/BIXtKuz+DHw9/VJZ0nmv1R7KyJAQ1ci7OuRmP1wzIc29LVhvB0+2bqLo+eQb3cCPq79ZroL7MBUrT+8esbIG4Onnq0/CJGw" +
        "Gp/Sw8rIunfrDSQ/eXtmzbRDLHqzaJ4Y5w9WXr0b18gTMnTrYd2pYoiN6M7DTKwj5y4H9FWYVhd+aGdDsEHiNJQpcxVv5YC5" +
        "zSxrNYYA+8wqSlZQYeDbEQ/r+KDd+RX/OwyPbT3Qu1IdN8Iwbh90lrM4WXXkYz0hkvXFA2NGtQFYqcpoBKZ7a6cjaj5lA7Qf" +
        "w05RIe0zku1ngMykWSwqOKitD34dfJXqgF+sWu1Jrnq0aL6TzQT+lebqNKZCx9F8ayblTo6fidZHoKhWHTF0+g/ctQrPFNVj" +
        "wBXY5xHdvm+VNuBF3fQUajFUL+yGgHY7ueQ117v3ZLa3+/90WQfDQe5etHbwMdl9TKeSX7ooPzGHTftjcq1O6xTTrevTWysy" +
        "bf2MzUBsLxEjNrZY6laHl5ZmGYFuVu4fK3lYT17H+2vNh9Zzvf6OdmhXb05ZY1l7LpRhsxQ8z1Ww7FG2+udk9mejLXIWj68W" +
        "tw5jmb0WgJW2NZILxSoLeDX7Rkm9fnj2MHbiW4X7WOYr8/Q8hkFpXFHpU6Dj40H/Jajd3t/8J3lZ0eo8sLZntXqNTHa9Vn2s" +
        "Px375hmYBTJNfTAeenn6G+/AWjE5ueHIzF/51+cf91KMf05ucwfLr/RhvvSwio7NEN2QtfptgOlj/RuiVTVvvz4AWt+hrOr+" +
        "+7VbFW2vWlkNq76xKunNLdKCWy1MzNoDSuOwWD58xxI/1uW22+SObDFGmcF6dvvlK6uGHk6nXD1bjwzHeATVrcCAYIvYpfdn" +
        "kf8Y8SSrB0oWm8Oqfe8Bz7TjSYsxALyhMhcroqf5weLzt1+XeHmKwdoA7eRXf3uknUUeelbO+vEmejAaD2eeQ81HopOqMq7P" +
        "KRZHO9fTy5BVzsM0y2C8ozz/Af6E9cMJZfVaV3Sb3h+89+2C8ZQC4quysWZEPKsNzDuwP87lE3fNG0/t1HOtpXexMdu1c71+" +
        "/ql7tBN7IyppjGH0rZ/bxpWAHt6m9mVhXRNZEeul3aH2/3Sh9oaZndb3AFIIiwlbkZjF/S6fLzJoZ4MPyRog2otu1XcHo7ST" +
        "+N+kHwDWSU4P0Y2HdXnAOyI6/F+VHGdmAsTyiqpOfaDCCftyNlTSNsvW/io2navqS6OyATuaw15c1E8m1pfKqfvcvpLpuFHj" +
        "lJ05qLf/Fp7pRlzmWO8PrKvXk9zWkyf/VuYePJzyGEPfU/9t9IsOogVAXYDxPFZ1qtpovbJ1swjUeuiNkVdYQ8dqiC03s2CZ" +
        "FQ3ZeOPa8elctJsrELbGDjus9X9UgurD+nY/uVOndTJ9mVCyLkH5hRoXAQM/b+3ItRoeOizjmi3Tf2ZGjx19s/FE8qfOdOja" +
        "Y/+QWBz1QawXmcg5YceRLx67Rl7ITPSS9vHrZSLPGbSyl+syNOOjmYxYmlVpqdS6rnMwtCeXrPAGTB8edTYkMhvvRa2JUxZR" +
        "hOXdaQs38xUUFXxoZ86ivfz3tFpPzdUyx29HpA60j13K/nFI43NYPEC7F7Oxrwdr6ofl+3C+RVbLZ4CJl4y5OngKtJcEdDj6" +
        "63hWLsNbfD1qWJDmHusAWp+H7JONfS1z7DOFMgqrXtZL2dhR+xjGt/qwcB7IwTW7f51DVuuoyT6NVvc2TYJvZYE0zRdkiWT3" +
        "yMzZFawHUyroC7mHc55c+dq6RdTRuq0k58m30S5k21VvsZvHw+5v7HWoHZYuPJnWnyXzfU7TLeJr57fPekilc/LPA14bzk/S" +
        "GgZ6ufJUkbNczk3tMTtzkHV4aQ/Ew3qpTuvlib4RqDHgexP//yjGCh7ViLsy3bOrhmTelmmN9IJx2mPvzMWJHHO/tT+zIrfK" +
        "SbvfLg0XvtpcfShLp/abDPQFmVGBt5kaYwP2HtlYddQxV8UxZmcPM9E96EwzyCYW/SGyJBLzIPf5ZcZFO7Tv5ic+9JLfzkXB" +
        "ZM2x9lCrqFJAVkhtKWfl3BLvhMv4ZpvVvaulP6gbslWWaLeSXnmVitg+AVUVuxl3RfdKQsWrIojgGFCekvKRS+IEGeo2Z7k/" +
        "7RmpXfjixMStRul0FhWmn966rC7MqkloB3bqBvwUaki1g6zaSvSrhtX6sIsf5PjNCanfe+MTVfvjKvMBeapoHTybIYqozi/M" +
        "nURakUB8HHp+NZYoo9RO5LqqPznLDuS7d+tuO41HOQxLCtYXZ2eUye4tkfOp4rCuh9p3AlF6VoZZ1NwbVq1Xsssf9iBJl1VC" +
        "DuYZLqKh74Qh7Zpyk/V8MKsxy+sv9xl4vh5yUKtVjVXeY8zA/Xt95Os3w6H+emmiTqF1MqjntXFqzNR5G6iTSNq5SzGRpKzS" +
        "blhEScqDTh2rGRXvIb6ufb2Ohz2zp0YpyEqjf2nmxClXkTnVSudYaWnriTld6jEyA8Poz81f9hcdpWFlWanT0EH5oJUVrd5S" +
        "udOtE1aS414cP/2YWdv1paWRiCJtpnurTeZhz9b2WI9L8t9GteqaalGRnLgSiZdk67ajbOmo/RUfzuySdXuyrYl6NzVbF7iG" +
        "vifMrld24pJnDuT7g6FOvke3fo7VOm1e6HTJerubVhIYumOu7LRV6lFZMR25FcSq0zqAT0UIOr2cXt536L9pPGnX6Vqikeph" +
        "9cPwyZpNycisGANGuTJf6ubH/BXlmrb5fKGTS4pW2aBzCv55AsdSbNLGKf7a3cdvfgH8tGJ2+1/3eOT/Yt6j5uHfPrNb5RQN" +
        "mxSR/pOr/5fjzVb7pdiwcnZll1Z0eBNrkJQRtNn7a1YgRZ2fMyySXRkbuaj8IvnTVcMTwAhBpB5TXLSvVI/ObIVIzvo1mwLb" +
        "fmuU87IxIHvTtfGI5Ixi2YLoFGNhvwRGtxKnB+2wCk/nVFYjZ8g4P07IhmZZb3je5WJfYPpF2tkQWPPQmFdr1hWhkmfN2tH/" +
        "YFX6k1R7ifdUlZt5YfeCcqAOVO+d7Idlk5mSsVl1phCqVjXDs7IjarXotE4w9xWd0LyYG6Or1zw8eh+Bi/hYfUsy5IpyQBnF" +
        "HmXLL7JzP/YnAMm4DtQn06OS5xRv6UEXZ0R5IT+cCwV5f34dRh11BOxCj+pnK+pQs9pP8avOh7pU119nO5ZxPF119VTMuDnt" +
        "SXbwqVgR17IY6PHB9IFsGVy1ZOC4d9Fv4MLp3Lr10imNj015GRWczUYNQN4lOSKHcVPeTkfa/x2r6SwDz/6RXjU2uh8OrdAx" +
        "GUqLdqd9jAeB6rVssdj7/2lroF01mzGKkCFh9LQRASqclrlmQ70QgiXtrSeeiJvd/GadgFL9SEm7oyadYJhDsSqSaVih/H8/" +
        "qCVuqygUHwuV/WS6HGadrTaY73O+E42cxnnbBT1y1jDSr9K3MJ/s2BsysRq9Mw95bafxRr/cMfA3xe17Gdf86eZ7TtaP+tGM" +
        "lnLhfhyaYN0L1WJvO+d/OfZRgRSf6onOpnH8zd6o5AsO7WKtFVyawUGl77DsWqNm1/o1duzfMMVz0846YLVvyjk8OV1ist/Y" +
        "sDlwxPJOTDy7tcZtAsn+iuzfFm06Sns0BvzOqLAS334z+GKT0zD/o5g1Ww23YLRT9bxsmPVw7catIKLklX1Z3bUhtzQ4I2p8" +
        "5JR2ciULc+H7TslqdaPl4+Qo1F4OO3E6F6lxUs3QamH6fE+zGoubfYdOzIN0mMahvRhVe0bzgUWq2Fd+nN6mhK7WVblSX6LL" +
        "k/jASWvH2SlnRYeZTr+QXfD+plfPSKypz05z99W3qVmX27g3j01pfGc+dE7b08iBlWOGiA2z1NuKWtSN0asy1dFPn7OmerLe" +
        "g/LrYjzg1Xo1Y7Ylw87fTqtfav3LvBbVw4n4S1p14t9j+U8y2VLgvbQWq/7wSLJHrMeD/ExWFjl6jznrhzqJ+iE6DZSWoPM0" +
        "lmhTVtDvlueYE8Ni0XlBsHjvtDJGiI9y6jdmnzHDNtG+arZV59QMf1sGohi6jMprzBEdxvhkn+PztKwZMz6LO+qbtYjDTmTU" +
        "ShPz9oP1XQ7WaT8Toc+KdvFkaCYC816LTRjEjCTN7zVjm717GOlLu6I5OedRR8n4kCgjZleg/phVNPL7m5XnEZavYwcWl7Vn" +
        "YlFtnniuHfdzX75dKz5X8+m1LvxjNRyJsvxwH1R+mGcc3hiyibNp3E9O5Izlxg5dmJqCrJoz9Fa1+2Ps6Gkes3FxEqZn1nO1" +
        "aT2+YxJT9fq+MXw5Z6IbDgOJ0f6D2r/m03o4lY/qhmG/7DmKacUfld73ZOxea9LBCexVbQC0QquaPyizQqOja1B/s7CjIwoq" +
        "1ntPK8iZbZvaQeBP5LO7WT8vJ83FrjGDWNLH+f7Lf5BZIG/6fYbxG9C3GVPknoP8n/naQP18M19fOfIzn2R5T50CbF12bqtc" +
        "Zy9pFznJIf06L823ow0j1Mz+p9qbXGPW/9a8dJusNaya3xuuuNlEZ53KrCelmw998+t/NpujM/Ltlg/YrEIpaveM2a3+WfHw" +
        "8uvFvPP5gjE9FBnibEXrST+M9eTsfCXrDf9Ybn8nhve1vM9pvdB2m0Ct3TWyzZiuxufyNvtWZ2R8TdqnTaNR9vFhqz9snsnx" +
        "q0x6EZ5sPR+1+0cYb35P8xPoUsZuy8FbPFt2VzPsq3Entt8MaNUgbRlWAbWyl6jTzO5Q5hJ82DVG5mdUk62REyXtjdWiO+ZE" +
        "FJPGPDpvGasoLk88UHPM6PY4DZl9udaDlR3OpoKqlGgWrFmVxUX8RKzio1zIuWh/si+ZwIhyUd21k9U+rI/Dur2TOMgVDt0Y" +
        "GNM6eOs1VmPaT+MOWBUr30H7zbJvi391ycv4c+Yr6DyfZPO2N8rSNEaGM/9T3nadJtkXe58N6/MzjbcxDKkIU/kum3nsik+/" +
        "nLlGXa3su6/xipoxpA6bDkouWnhnqE3jRhc7iZtxC7QTz+TaaL/uuWx2RsZvjvR4u7y4k9l7kZPqrA+ezXX1ZO0UzdNDd+Hu" +
        "nKLMLmToHCjGwc7OiLS3BbXAgDmVQa6diDu9mEHPsaQm33SYaOfQFZcdehwYCohzEqeRfdXKgac394c8GcaIaSAz0IiH6cw1" +
        "5fyfSaNf1IMxpzW1PyB6brP33wqG4Xqd77x6TsHtlTioK5i0R3Rsxzr4zKqdZLNZPLOu3dh38NX49f0kb0/in3IYSpIs8gTn" +
        "TWK1+3HoMJqK5Wfwi12FnE6V8ZjRinUFh8nt07pjUJIcKgLUM9Cpisj+hNbkz43SdnH2sjzJbOSvOda/SDwxtU/yqX1J3OvN" +
        "oQ+QZ2dFZbMVzO48prGJzlv7sXjulfbE8vjZ5MiZ5oyOPRXFz5zlk9bfTIFi2LfIfjvf6htOdneGJWeL14Ce3Zw006zKD1Zy" +
        "p99VU1CvyqZWBpvT9bGs/W543W6eoOfUz2Y4qktA+5JV03lOiLqtS2pjl/CvzX+5jT3NXiUB3a2zzZrY1t3Q2WCa5d6+L3fV" +
        "y/c8ppFfNteF86PiThlGrqhaJh8VHqMoKlDiZL3Lq4GiZj/jbjMgRJfmi1gMc3rL/b1Vg+2MRCQOVF4B583IdeVZONej7eE3" +
        "YTZ1Z9lrm35ofKrNzt/+m8jzNfudjf1HyUgjBKsim7SzN/XqbhpmWN5MJyhaNbhVf2kW6jT+jFavKcOQ6L4hYvPMxmrSXuQ4" +
        "XatJpdYFI/vSbe7DSmz4Zv7TTc083YvOPmCulpMmyo//MJp2ZJjsKfrPv4jsoalYSdymzYt7J6ufDr7baGQ2yTW/Wqv3+DFp" +
        "zdsovx6TOutOIzP0LtbO5HUXjb1Fna68vLMI4Pe1it6M3jqWg+M/6hyiy3fOHEVXSHG5weCpOmtdkSNgmBc5I36YHXP0Npq9" +
        "k3WQ8rCGvsAmaUXW+Ktm7b/MgH8NfeE873iyR+QwZpw8TXUTNRCNZ2HoBMCzemd9n5hTr5lrny3iRwZiPF9OcKnGXdd5wFit" +
        "768iLbuXXzi+uNPbzZ49cLeyaO5yZxdb+Nbd+hfTAxvag2oY23zlOazMTlAnFmVaz7cjbpQ4riPyZfcP7oGcpk3R0bdzItFx" +
        "x5kVA/Y7VuO8io5drT4Z1qR/ip1HRknpoly7rJ/yvypWlYeblaNvf9l3arDiwNorHtZrvBXyZdis8i1nY8kr8/J4tEJTn2SS" +
        "0aDWBPko9iipu9aGpN08mKFd+ogYxUJfz+U5rJP7pdUO8Y0DEbmhV756HZtNJEWHSPj00A7X5gz11LMm31neiToiv1Gr8Nnj" +
        "cXtnaj2MUMZHKwccf0Yn1qKHrvWPcGrXx3PZ3MTinOGVH66BdnSVr/y91Zrsm1iUAYDu7+wxncfn1DxTHRYraWejmg/zxNTH" +
        "QldKOdPyU51zT0MZxr9FPYxWls+fdjzJJS6casSZpiM+4HzQQqTXQ3fsu9+tukCeF1PjBvIFcoQTJzpOZK3Pu5fvr29qiZhS" +
        "dlPPyJp75bEE08PeIn5lAjbjKn8tftUp2ccSLmNtO3R9vgwXkNMym0c/e+ClwNhckSiF7G3OmgeOP+tcLdJHXFs+nJWYy9is" +
        "olXRKaxQ5aS+fvbTW/wRq9b/cILs0qZyQN3y3UNGbcDBSevKA+upWTXdqD3e2rUZOi5j3jpOVAMLgzI0fG/dJEuZIrfl4YPN" +
        "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"

    private val table: ByteArray by lazy {
        val compressed = java.util.Base64.getDecoder().decode(DATA)
        val out = ByteArray(SIZE * 2)
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(compressed)
            var offset = 0
            while (offset < out.size) {
                val read = inflater.inflate(out, offset, out.size - offset)
                if (read == 0) break
                offset += read
            }
        } finally {
            inflater.end()
        }
        out
    }

    /**
     * 码位的所有声母（小写，最多两个，按常见程度排序）；不是汉字或表里
     * 没有读音时返回空串。
     */
    fun initialsOf(codePoint: Int): String {
        val index = codePoint - START
        if (index < 0 || index >= SIZE) return ""
        val first = table[index * 2].toInt()
        val second = table[index * 2 + 1].toInt()
        return buildString(2) {
            if (first != 0) append('a' + first - 1)
            if (second != 0) append('a' + second - 1)
        }
    }
}
