import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { ServiceWorkerModule } from '@angular/service-worker';
import { environment } from '../environments/environment';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { DaHeaderComponent } from './da-header/da-header.component';
import { DaSidebarComponent } from './da-sidebar/da-sidebar.component';
import { AngularSplitModule } from 'angular-split';
import { DaDataComponent } from './da-data/da-data.component';
import { DaDataPipe } from './services/da-data.pipe';
import { DaFooterComponent } from './da-footer/da-footer.component';
import { FormsModule } from '@angular/forms';
import { DaVarDirective } from './services/da-var.directive';
import { DaFormatPipe } from './services/da-format.pipe';
import { DaTopComponent } from './da-top/da-top.component';


@NgModule({
  declarations: [
    AppComponent,
    DaHeaderComponent,
    DaSidebarComponent,
    DaDataComponent,
    DaDataPipe,
    DaFooterComponent,
    DaVarDirective,
    DaFormatPipe,
    DaTopComponent
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    HttpClientModule,
    FormsModule,
    AngularSplitModule,
    ServiceWorkerModule.register('ngsw-worker.js', {
      enabled: environment.production,
      // Register the ServiceWorker as soon as the app is stable
      // or after 30 seconds (whichever comes first).
      registrationStrategy: 'registerWhenStable:30000'
    })
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
