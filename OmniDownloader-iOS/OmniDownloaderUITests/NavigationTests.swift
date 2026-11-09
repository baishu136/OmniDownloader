import XCTest

final class NavigationTests:XCTestCase {
    @MainActor func testFourPagesAndCategorizedSettings(){
        let app=XCUIApplication()
        app.launchArguments=["-AppleLanguages","(zh-Hans)","-AppleLocale","zh_CN","-omni.language","zhCN"]
        app.launch()
        XCTAssertTrue(app.textFields["omni.video.url"].waitForExistence(timeout:15))
        let home=XCTAttachment(screenshot:app.screenshot());home.name="iOS-home";home.lifetime = .keepAlways;add(home)
        app.buttons["omni.nav.3"].tap()
        XCTAssertTrue(app.staticTexts["Cookie 与登录管理"].waitForExistence(timeout:5))
        for title in ["外观与语言","下载与存储","网络代理","中转站","账号","下载引擎","关于"]{XCTAssertTrue(app.staticTexts[title].exists,title)}
        app.buttons["omni.settings.accounts"].tap()
        XCTAssertTrue(app.staticTexts["Cookie 与登录管理"].waitForExistence(timeout:5))
        let settings=XCTAttachment(screenshot:app.screenshot());settings.name="iOS-accounts";settings.lifetime = .keepAlways;add(settings)
        app.buttons["omni.nav.1"].tap()
        XCTAssertTrue(app.textFields["omni.images.url"].waitForExistence(timeout:5))
        app.buttons["omni.nav.2"].tap()
        XCTAssertTrue(app.staticTexts["下载任务"].waitForExistence(timeout:5))
    }
}
