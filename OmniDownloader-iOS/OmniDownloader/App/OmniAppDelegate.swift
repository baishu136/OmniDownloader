import UIKit

final class OmniAppDelegate:NSObject,UIApplicationDelegate{
    func application(_ application:UIApplication,handleEventsForBackgroundURLSession identifier:String,completionHandler:@escaping()->Void){BackgroundTransfer.shared.restore(identifier,completion:completionHandler)}
}
