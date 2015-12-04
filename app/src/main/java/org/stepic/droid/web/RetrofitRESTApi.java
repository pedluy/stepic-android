package org.stepic.droid.web;

import android.os.AsyncTask;
import android.os.Looper;
import android.util.Log;

import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.yandex.metrica.YandexMetrica;

import org.stepic.droid.base.MainApplication;
import org.stepic.droid.configuration.IConfig;
import org.stepic.droid.core.ScreenManager;
import org.stepic.droid.model.Course;
import org.stepic.droid.model.EnrollmentWrapper;
import org.stepic.droid.preferences.SharedPreferenceHelper;
import org.stepic.droid.store.operations.DatabaseManager;
import org.stepic.droid.util.AppConstants;
import org.stepic.droid.util.JsonHelper;
import org.stepic.droid.util.RWLocks;

import java.io.IOException;
import java.net.Proxy;

import javax.inject.Inject;
import javax.inject.Singleton;

import retrofit.Call;
import retrofit.GsonConverterFactory;
import retrofit.Retrofit;

@Singleton
public class RetrofitRESTApi implements IApi {
    @Inject
    SharedPreferenceHelper mSharedPreference;
    @Inject
    ScreenManager screenManager;
    @Inject
    DatabaseManager mDbManager;
    @Inject
    IConfig mConfig;

    private StepicRestLoggedService mLoggedService;
    private StepicRestOAuthService mOAuthService;


    public RetrofitRESTApi() {
        MainApplication.component().inject(this);

        OkHttpClient okHttpClient = new OkHttpClient();
        setAuthenticatorClientIDAndPassword(okHttpClient);
        Retrofit notLogged = new Retrofit.Builder()
                .baseUrl(mConfig.getBaseUrl())
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient)
                .build();
        mOAuthService = notLogged.create(StepicRestOAuthService.class);


        okHttpClient = new OkHttpClient();
        Interceptor interceptor = new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                RWLocks.AuthLock.writeLock().lock();
                try {
                    Request newRequest = chain.request();
                    AuthenticationStepicResponse response = mSharedPreference.getAuthResponseFromStore();
                    if (response != null) {
                        Log.i("Thread", Looper.myLooper() == Looper.getMainLooper() ? "main" : Thread.currentThread().getName());
                        response = mOAuthService.updateToken(mConfig.getRefreshGrantType(), response.getRefresh_token()).execute().body();//todo: Which Thread is it?
                        mSharedPreference.storeAuthInfo(response);
                        newRequest = chain.request().newBuilder().addHeader("Authorization", getAuthHeaderValue()).build();
                    }
                    return chain.proceed(newRequest);
                } finally {
                    RWLocks.AuthLock.writeLock().unlock();
                }

            }
        };
        okHttpClient.networkInterceptors().add(interceptor);
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(mConfig.getBaseUrl())
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient)
                .build();
        mLoggedService = retrofit.create(StepicRestLoggedService.class);
    }

    @Override
    public Call<AuthenticationStepicResponse> authWithLoginPassword(String login, String password) {
        YandexMetrica.reportEvent("Api:auth with login password");
        return mOAuthService.authWithLoginPassword(mConfig.getGrantType(), login, password);
    }

    @Override
    public Call<IStepicResponse> signUp(String firstName, String secondName, String email, String password) {
        throw new RuntimeException("not implemented");
    }

    public Call<CoursesStepicResponse> getEnrolledCourses(int page) {
        YandexMetrica.reportEvent("Api: get enrolled courses");
        return mLoggedService.getEnrolledCourses(true, page);
    }

    public Call<CoursesStepicResponse> getFeaturedCourses(int page) {
        YandexMetrica.reportEvent("Api:get featured courses)");
        return mLoggedService.getFeaturedCourses(true, page);
    }

    @Override
    public Call<StepicProfileResponse> getUserProfile() {
        YandexMetrica.reportEvent("Api:get user profile");
        return mLoggedService.getUserProfile();
    }

    @Override
    public Call<UserStepicResponse> getUsers(long[] userIds) {
        YandexMetrica.reportEvent("Api:get users");
        return mLoggedService.getUsers(userIds);
    }

    @Override
    public Call<Void> tryJoinCourse(Course course) {
        YandexMetrica.reportEvent("Api:try join to course", JsonHelper.toJson(course));
        EnrollmentWrapper enrollmentWrapper = new EnrollmentWrapper(course.getCourseId());
        return mLoggedService.joinCourse(enrollmentWrapper);
    }

    @Override
    public Call<SectionsStepicResponse> getSections(long[] sectionsIds) {
        YandexMetrica.reportEvent("Api:get sections", JsonHelper.toJson(sectionsIds));
        return mLoggedService.getSections(sectionsIds);
    }

    @Override
    public Call<UnitStepicResponse> getUnits(long[] units) {
        YandexMetrica.reportEvent("Api:get units", JsonHelper.toJson(units));
        return mLoggedService.getUnits(units);
    }

    @Override
    public Call<LessonStepicResponse> getLessons(long[] lessons) {
        YandexMetrica.reportEvent("Api:get lessons", JsonHelper.toJson(lessons));
        return mLoggedService.getLessons(lessons);
    }

    @Override
    public Call<StepResponse> getSteps(long[] steps) {
        YandexMetrica.reportEvent("Api:get steps", JsonHelper.toJson(steps));
        return mLoggedService.getSteps(steps);
    }

    @Override
    public Call<Void> dropCourse(long courseId) {
        YandexMetrica.reportEvent("Api: " + AppConstants.METRICA_DROP_COURSE, JsonHelper.toJson(courseId));
        return mLoggedService.dropCourse(courseId);
    }

    @Override
    public Call<ProgressesResponse> getProgresses(String[] progresses) {
        YandexMetrica.reportEvent("Api: " + AppConstants.METRICA_GET_PROGRESSES);
        return mLoggedService.getProgresses(progresses);
    }

    @Override
    public Call<AssignmentResponse> getAssignments(long[] assignmentsIds) {
        YandexMetrica.reportEvent("Api: " + AppConstants.METRICA_GET_ASSIGNMENTS);
        return mLoggedService.getAssignments(assignmentsIds);
    }

    @Override
    public Call<Void> postViewed(ViewAssignment stepAssignment) {
        return mLoggedService.postViewed(new ViewAssignmentWrapper(stepAssignment.getAssignment(), stepAssignment.getStep()));
    }

    private void setAuthenticatorClientIDAndPassword(OkHttpClient httpClient) {
        httpClient.setAuthenticator(new Authenticator() {
            //            private int mCounter = 0;
            @Override
            public Request authenticate(Proxy proxy, Response response) throws IOException {
//                if (mCounter++ > 0) {
//                    throw new AuthException();
//                }
                String credential = Credentials.basic(mConfig.getOAuthClientId(), mConfig.getOAuthClientSecret());
                return response.request().newBuilder().header("Authorization", credential).build();
            }

            @Override
            public Request authenticateProxy(Proxy proxy, Response response) throws IOException {
                return null;
            }
        });
    }

    private String getAuthHeaderValue() {
        try {
            AuthenticationStepicResponse resp = mSharedPreference.getAuthResponseFromStore();
            String access_token = resp.getAccess_token();
            String type = resp.getToken_type();
            return type + " " + access_token;
        } catch (Exception ex) {
            YandexMetrica.reportError("retrofitAuth", ex);
            Log.e("retrofitAuth", ex.getMessage());
            // FIXME: 19.11.15 It not should happen

            mSharedPreference.deleteAuthInfo();
            AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    mDbManager.clearCacheCourses(DatabaseManager.Table.enrolled);
                    return null;
                }
            };
            task.execute();
            screenManager.showLaunchScreen(MainApplication.getAppContext(), false);
            // FIXME: 19.11.15 ^^^^^^
            return "";
        }
    }
}