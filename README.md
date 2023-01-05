# Help

Program that refreshes your resume on <https://www.naukri.com/>, <https://www.monsterindia.com/> & <https://instahyre.com>.

If you have account only one of the job portals, you can only specify the corresponding credentials when running the application.

1. For monster india, these are the environmental variables that needs to be set

```shell
app_monster_username='<monster username>' app_monster_password='<monster password>'
```

2. For instahyre, these are the environmental variables that needs to be set

```shell
app_instahyre_username='<instahyre username>' app_instahyre_password='<instahyre password>'
```

3. For naukri, these are the environmental variables that needs to be set

```shell
app_naukri_username='<naukri username>' app_naukri_password='<naukri password>'
```

## How to run

```shell
# If you have accounts only in some of these job portals
app_monster_username='<monster username>' \
app_monster_password='<monster password>' \
app_instahyre_username='<instahyre username>' \
app_instahyre_password='<instahyre password>' \
/path/to/resume-refresher \
--app.resume.path="<path to pdf/docx>" \
--app.resume.filename="<resume.pdf/docx>"

# If you have accounts in all job portals
app_monster_username='<monster username>' \
app_monster_password='<monster password>' \
app_instahyre_username='<instahyre username>' \
app_instahyre_password='<instahyre password>' \
app_naukri_username='<naukri username>' \
app_naukri_password='<naukri password>' \
/path/to/resume-refresher \
--app.resume.path="<path to pdf/docx>" \
--app.resume.filename="<resume.pdf/docx>"

# If you want to debug (this example assumes you only have account in monster india)
app_monster_username='<monster username>' \
app_monster_password='<monster password>' \
/path/to/resume-refresher \
--app.resume.path="<path to pdf/docx>" \
--app.resume.filename="<resume.pdf/docx>" \
--app.debug
```

## How to Build

1. Ensure you have atleast Java 17 installed
2. Run `./gradlew nativeCompile` which generates native executable under this path `build/native/nativeCompile/resume-refresher` (generated executable has multiple issues. See [here](https://github.com/spring-projects-experimental/spring-native/issues/1698) & [here](https://github.com/spring-projects-experimental/spring-native/issues/1694))
3. If above one doesnt work, run `./gradlew bootJar` which generates executable jar under this path `build/libs/resume-refresher-0.0.1-SNAPSHOT.jar`. In the above script instead of specifying `/path/to/resume-refresher`, instead use `java -jar resume-refresher-0.0.1-SNAPSHOT.jar`. It will work.

> NOTE: You may need to run `gradlew.bat nativeCompile`/`gradlew bootJar` in windows to generate the image & run generated `build/native/nativeCompile/resume-refresher`/`build/libs/resume-refresher-0.0.1-SNAPSHOT.jar` using command line without `eval`

## Schedule cron job in Ubuntu Desktop

1. Create following script `resume-refresh-with-desktop-notifications.sh`
```shell
#!/usr/bin/sh

# required for notify-send to work properly and send desktop notifications to current user
export XDG_RUNTIME_DIR=/run/user/$(id -u)

app_naukri_password='<naukri password>' \
app_monster_password='<monster password>' \
app_instahyre_password='<instahyre password>' \
/path/to/resume-refresher \
--app.resume.path="<path to pdf/docx>" \
--app.resume.filename="<resume.pdf/docx>" \
--app.naukri.username="<naukri username>" \
--app.monster.username="<monster username>" \
--app.instahyre.username="<instahyre username>" \
--app.debug \
>"<path to log>" 2>&1

grep "#block terminated with an error" "<path to log>"

# Desktop notifications in ubuntu
if [ $? != 0 ]; then
  /usr/bin/notify-send -t 0 "SUCCESS: Resume refresh" "<path to log>";
else
  /usr/bin/notify-send -u critical -t 0 "FAIL: Resume refresh" "<path to log>";
fi
```
2. Schedule cron job by running `crontab -e` & Add this
```shell
# run resume refresh program every 30mins
*/30 * * * * <path to resume-refresh-with-desktop-notifications.sh>
```
3. List existing cron jobs using `crontab -l`
